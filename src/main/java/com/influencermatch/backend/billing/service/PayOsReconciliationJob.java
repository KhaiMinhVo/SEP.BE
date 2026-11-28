package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.config.PayOsConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayOsReconciliationJob {
  private final PaymentRepository payments;
  private final PaymentSettlementService settlement;
  private final PayOsPaymentClient client;
  private final PayOsConfig config;
  private final PlatformTransactionManager manager;

  @Scheduled(fixedDelayString = "${application.payos.reconciliation-delay-ms:60000}")
  public void reconcile() {
    if (!config.isConfigured()) return;
    var before = LocalDateTime.now().minusMinutes(1);
    var expirationCutoff = LocalDateTime.now().minusMinutes(15);
    var candidates =
        payments.findPayOsReconciliationCandidates(before, PageRequest.of(0, 20));

    for (var candidate : candidates) {
      try {
        var tx = new TransactionTemplate(manager);
        Boolean claimed = tx.execute(status -> {
          var payment =
              settlement.lock(payments.findById(candidate.getId()).orElseThrow());
          if (payment.getStatus() != PaymentStatus.PENDING
              || (payment.getLastReconciledAt() != null
                  && !payment.getLastReconciledAt().isBefore(before))) {
            return false;
          }
          payment.setLastReconciledAt(LocalDateTime.now());
          payments.save(payment);
          return true;
        });
        if (!Boolean.TRUE.equals(claimed)) continue;

        long orderCode = Long.parseLong(candidate.getTransactionCode());
        var link = client.getPaymentLink(orderCode);
        if (!matchesPayment(candidate.getAmount(), orderCode, link)) {
          log.warn("PayOS reconciliation response mismatch for orderCode={}", orderCode);
          continue;
        }

        if (link.getStatus() == PaymentLinkStatus.PENDING
            && !candidate.getCreatedAt().isAfter(expirationCutoff)) {
          link = client.cancelPaymentLink(orderCode);
          if (!matchesPayment(candidate.getAmount(), orderCode, link)) {
            log.warn("PayOS cancellation response mismatch for orderCode={}", orderCode);
            continue;
          }
        }

        boolean paid = link.getStatus() == PaymentLinkStatus.PAID;
        boolean terminalWithoutPayment =
            link.getAmountPaid() == 0L
                && (link.getStatus() == PaymentLinkStatus.CANCELLED
                    || link.getStatus() == PaymentLinkStatus.EXPIRED
                    || link.getStatus() == PaymentLinkStatus.FAILED);
        if (paid && link.getAmountPaid().compareTo(link.getAmount()) != 0) {
          log.warn("PayOS reports PAID with an incomplete amount for orderCode={}", orderCode);
          continue;
        }
        if (!paid && !terminalWithoutPayment) continue;

        boolean succeeded = paid;
        String failureReason = terminalWithoutPayment ? "PAYMENT_EXPIRED" : null;
        tx.executeWithoutResult(status -> {
          var payment =
              settlement.lock(payments.findById(candidate.getId()).orElseThrow());
          if (!settlement.apply(payment, succeeded, failureReason)) {
            log.warn(
                "PayOS settlement conflict requires investigation: orderCode={}",
                candidate.getTransactionCode());
          }
        });
      } catch (Exception error) {
        log.warn(
            "PayOS reconciliation pending for orderCode={}: {}",
            candidate.getTransactionCode(),
            error.getClass().getSimpleName());
      }
    }
  }

  private boolean matchesPayment(
      BigDecimal expectedAmount,
      long expectedOrderCode,
      vn.payos.model.v2.paymentRequests.PaymentLink link) {
    return link != null
        && link.getOrderCode() != null
        && link.getOrderCode() == expectedOrderCode
        && link.getAmount() != null
        && link.getAmountPaid() != null
        && expectedAmount.compareTo(BigDecimal.valueOf(link.getAmount())) == 0
        && link.getAmountPaid() >= 0
        && link.getAmountPaid() <= link.getAmount();
  }
}
