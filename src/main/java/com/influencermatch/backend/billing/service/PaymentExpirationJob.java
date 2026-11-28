package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentExpirationJob {
  private final PaymentRepository payments;
  private final PaymentSettlementService settlement;

  @Scheduled(fixedDelayString = "${application.payment.expiration-delay-ms:60000}")
  @Transactional
  public void expirePendingCheckouts() {
    LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(15);
    var candidates =
        payments.findExpiredPendingCheckouts(expiredAt, PageRequest.of(0, 50));

    for (var candidate : candidates) {
      try {
        var payment = settlement.lock(candidate);
        if (payment.getStatus() != PaymentStatus.PENDING) continue;
        if (settlement.apply(payment, false, "PAYMENT_EXPIRED")) {
          log.info(
              "Expired pending payment txnRef={} after 15 minutes",
              payment.getTransactionCode());
        }
      } catch (Exception error) {
        log.warn(
            "Could not expire pending payment txnRef={}: {}",
            candidate.getTransactionCode(),
            error.getClass().getSimpleName());
      }
    }
  }
}
