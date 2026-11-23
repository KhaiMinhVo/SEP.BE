package com.influencermatch.backend.billing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.billing.service.PaymentExpirationJob;
import com.influencermatch.backend.billing.service.PaymentSettlementService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentExpirationJobTest {
  @Test
  void expiresPendingCheckoutAndSubscription() {
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentSettlementService settlement = mock(PaymentSettlementService.class);
    Payment payment =
        Payment.builder()
            .transactionCode("TXN-EXPIRED")
            .status(PaymentStatus.PENDING)
            .createdAt(LocalDateTime.now().minusMinutes(20))
            .build();

    when(payments.findExpiredPendingCheckouts(any(LocalDateTime.class), any()))
        .thenReturn(List.of(payment));
    when(settlement.lock(payment)).thenReturn(payment);
    when(settlement.apply(payment, false, "PAYMENT_EXPIRED")).thenReturn(true);

    new PaymentExpirationJob(payments, settlement).expirePendingCheckouts();

    verify(settlement).apply(payment, false, "PAYMENT_EXPIRED");
  }

  @Test
  void ignoresPaymentThatWasSettledBeforeExpiration() {
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentSettlementService settlement = mock(PaymentSettlementService.class);
    Payment payment =
        Payment.builder()
            .transactionCode("TXN-PAID")
            .status(PaymentStatus.PAID)
            .createdAt(LocalDateTime.now().minusMinutes(20))
            .build();

    when(payments.findExpiredPendingCheckouts(any(LocalDateTime.class), any()))
        .thenReturn(List.of(payment));
    when(settlement.lock(payment)).thenReturn(payment);

    new PaymentExpirationJob(payments, settlement).expirePendingCheckouts();

    verify(settlement, never()).apply(eq(payment), eq(false), eq("PAYMENT_EXPIRED"));
  }
}
