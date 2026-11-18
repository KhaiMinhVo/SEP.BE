package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.config.VnPayConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Slf4j
public class PaymentReconciliationJob {
  private final PaymentRepository payments;
  private final PaymentSettlementService settlement;
  private final VnPayQueryClient client;
  private final VnPayConfig config;
  private final TransactionTemplate tx;

  public PaymentReconciliationJob(PaymentRepository payments, PaymentSettlementService settlement,
      VnPayQueryClient client, VnPayConfig config, PlatformTransactionManager manager) {
    this.payments = payments;
    this.settlement = settlement;
    this.client = client;
    this.config = config;
    this.tx = new TransactionTemplate(manager);
  }

  @Scheduled(fixedDelayString = "${application.vnpay.reconciliation-delay-ms:60000}")
  public void reconcile() {
    if (!config.isReconciliationEnabled() || config.getHashSecret() == null
        || config.getHashSecret().isBlank()) return;
    var before = LocalDateTime.now().minusMinutes(5);
    var candidates = payments.findReconciliationCandidates(before, PageRequest.of(0, 20));
    for (var candidate : candidates) {
      try {
        // Claim before HTTP, so multiple instances do not continuously query the same payment.
        Boolean claimed = tx.execute(status -> {
          var payment = settlement.lock(payments.findById(candidate.getId()).orElseThrow());
          if (payment.getStatus() != PaymentStatus.PENDING
              || (payment.getLastReconciledAt() != null && !payment.getLastReconciledAt().isBefore(before))) return false;
          payment.setLastReconciledAt(LocalDateTime.now());
          payments.save(payment);
          return true;
        });
        if (!Boolean.TRUE.equals(claimed)) continue;
        var result = client.query(candidate.getTransactionCode(), candidate.getGatewayCreateDate());
        tx.executeWithoutResult(status -> {
          var payment = settlement.lock(payments.findById(candidate.getId()).orElseThrow());
          var expected = payment.getAmount().multiply(BigDecimal.valueOf(100));
          if (expected.compareTo(new BigDecimal(result.get("vnp_Amount"))) != 0)
            throw new IllegalStateException("VNPay query amount mismatch");
          String outcome = result.get("vnp_TransactionStatus");
          boolean resolved = true;
          if ("00".equals(outcome)) resolved = settlement.apply(payment, true, null);
          else if ("02".equals(outcome)) resolved = settlement.apply(payment, false, "VNPAY_QUERY_FAILED");
          if (!resolved) log.warn("VNPay settlement conflict requires investigation: txnRef={}",
              payment.getTransactionCode());
          // Unfinished, reversed and unknown outcomes remain pending for investigation/retry.
        });
      } catch (Exception error) {
        log.warn("VNPay reconciliation pending for txnRef={}: {}", candidate.getTransactionCode(),
            error.getClass().getSimpleName());
      }
    }
  }
}
