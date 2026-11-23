package com.influencermatch.backend.billing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.billing.service.PayOsPaymentClient;
import com.influencermatch.backend.billing.service.PayOsReconciliationJob;
import com.influencermatch.backend.billing.service.PaymentSettlementService;
import com.influencermatch.backend.config.PayOsConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;

class PayOsReconciliationJobTest {
  @Test
  void reconcilesProviderPaidPayment() {
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentSettlementService settlement = mock(PaymentSettlementService.class);
    PayOsPaymentClient client = mock(PayOsPaymentClient.class);
    PayOsConfig config = new PayOsConfig();
    config.setClientId("client");
    config.setApiKey("api");
    config.setChecksumKey("checksum");
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());

    Payment payment =
        Payment.builder()
            .id(UUID.randomUUID())
            .transactionCode("1795464282007709")
            .paymentGateway("PAYOS")
            .status(PaymentStatus.PENDING)
            .amount(new BigDecimal("2000"))
            .createdAt(LocalDateTime.now().minusMinutes(20))
            .build();
    when(payments.findPayOsReconciliationCandidates(any(), any()))
        .thenReturn(List.of(payment));
    when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
    when(settlement.lock(payment)).thenReturn(payment);
    PaymentLink paidLink = new PaymentLink();
    paidLink.setId("payment-link-id");
    paidLink.setOrderCode(1795464282007709L);
    paidLink.setAmount(2000L);
    paidLink.setAmountPaid(2000L);
    paidLink.setAmountRemaining(0L);
    paidLink.setStatus(PaymentLinkStatus.PAID);
    when(client.getPaymentLink(1795464282007709L)).thenReturn(paidLink);
    when(settlement.apply(payment, true, null)).thenReturn(true);

    new PayOsReconciliationJob(payments, settlement, client, config, manager).reconcile();

    verify(settlement).apply(payment, true, null);
  }

  @Test
  void cancelsUnpaidPayOsLinkAfter15Minutes() {
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentSettlementService settlement = mock(PaymentSettlementService.class);
    PayOsPaymentClient client = mock(PayOsPaymentClient.class);
    PayOsConfig config = configuredPayOs();
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());

    Payment payment = pendingPayment(LocalDateTime.now().minusMinutes(20));
    when(payments.findPayOsReconciliationCandidates(any(), any()))
        .thenReturn(List.of(payment));
    when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
    when(settlement.lock(payment)).thenReturn(payment);
    when(client.getPaymentLink(1795464282007709L))
        .thenReturn(paymentLink(PaymentLinkStatus.PENDING, 0L));
    when(client.cancelPaymentLink(1795464282007709L))
        .thenReturn(paymentLink(PaymentLinkStatus.CANCELLED, 0L));

    new PayOsReconciliationJob(payments, settlement, client, config, manager).reconcile();

    verify(client).cancelPaymentLink(1795464282007709L);
    verify(settlement).apply(payment, false, "PAYMENT_EXPIRED");
  }

  @Test
  void doesNotCancelRecentUnpaidPayOsLink() {
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentSettlementService settlement = mock(PaymentSettlementService.class);
    PayOsPaymentClient client = mock(PayOsPaymentClient.class);
    PayOsConfig config = configuredPayOs();
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());

    Payment payment = pendingPayment(LocalDateTime.now().minusMinutes(10));
    when(payments.findPayOsReconciliationCandidates(any(), any()))
        .thenReturn(List.of(payment));
    when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
    when(settlement.lock(payment)).thenReturn(payment);
    when(client.getPaymentLink(1795464282007709L))
        .thenReturn(paymentLink(PaymentLinkStatus.PENDING, 0L));

    new PayOsReconciliationJob(payments, settlement, client, config, manager).reconcile();

    verify(client, org.mockito.Mockito.never()).cancelPaymentLink(1795464282007709L);
    verify(settlement, org.mockito.Mockito.never())
        .apply(payment, false, "PAYMENT_EXPIRED");
  }

  private PayOsConfig configuredPayOs() {
    PayOsConfig config = new PayOsConfig();
    config.setClientId("client");
    config.setApiKey("api");
    config.setChecksumKey("checksum");
    return config;
  }

  private Payment pendingPayment(LocalDateTime createdAt) {
    return Payment.builder()
        .id(UUID.randomUUID())
        .transactionCode("1795464282007709")
        .paymentGateway("PAYOS")
        .status(PaymentStatus.PENDING)
        .amount(new BigDecimal("2000"))
        .createdAt(createdAt)
        .build();
  }

  private PaymentLink paymentLink(PaymentLinkStatus status, long amountPaid) {
    PaymentLink link = new PaymentLink();
    link.setId("payment-link-id");
    link.setOrderCode(1795464282007709L);
    link.setAmount(2000L);
    link.setAmountPaid(amountPaid);
    link.setAmountRemaining(2000L - amountPaid);
    link.setStatus(status);
    return link;
  }
}
