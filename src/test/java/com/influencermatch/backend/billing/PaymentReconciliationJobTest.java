package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.config.VnPayConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PaymentReconciliationJobTest {
  final PaymentRepository payments=mock(PaymentRepository.class);
  final PaymentSettlementService settlement=mock(PaymentSettlementService.class);
  final VnPayQueryClient client=mock(VnPayQueryClient.class);
  final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
  final VnPayConfig config=new VnPayConfig();
  final Payment payment=Payment.builder().id(UUID.randomUUID()).status(PaymentStatus.PENDING)
      .amount(new BigDecimal("100")).transactionCode("TX").gatewayCreateDate("20261118100000")
      .createdAt(LocalDateTime.now().minusMinutes(20)).build();
  final PaymentReconciliationJob job=new PaymentReconciliationJob(payments,settlement,client,config,manager);
  @BeforeEach void setup() {
    config.setHashSecret("test-secret");
    when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
    when(payments.findReconciliationCandidates(any(),any())).thenReturn(List.of(payment));
    when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
    when(settlement.lock(payment)).thenReturn(payment);
  }
  @Test void missedCallbackIsRecoveredAndClaimPreventsImmediateRepeat() {
    when(client.query("TX",payment.getGatewayCreateDate()))
        .thenReturn(Map.of("vnp_Amount","10000","vnp_TransactionStatus","00"));
    job.reconcile(); job.reconcile();
    verify(client,times(1)).query("TX",payment.getGatewayCreateDate());
    verify(settlement).apply(payment,true,null);
    assertThat(payment.getLastReconciledAt()).isNotNull();
  }
  @Test void timeoutLeavesPaymentPending() {
    when(client.query(anyString(),anyString())).thenThrow(new IllegalStateException("timeout"));
    job.reconcile();
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    verify(settlement,never()).apply(any(),anyBoolean(),any());
  }
  @ParameterizedTest @ValueSource(strings={"01","04","07","99"})
  void unknownStatusDoesNotFailPayment(String outcome) {
    when(client.query(anyString(),anyString())).thenReturn(Map.of("vnp_Amount","10000","vnp_TransactionStatus",outcome));
    job.reconcile(); verify(settlement,never()).apply(any(),anyBoolean(),any());
  }
  @Test void incorrectAmountCannotActivate() {
    when(client.query(anyString(),anyString())).thenReturn(Map.of("vnp_Amount","20000","vnp_TransactionStatus","00"));
    job.reconcile(); verify(settlement,never()).apply(any(),anyBoolean(),any());
  }
  @Test void confirmedFailureIsSettled() {
    when(client.query(anyString(),anyString())).thenReturn(Map.of("vnp_Amount","10000","vnp_TransactionStatus","02"));
    job.reconcile(); verify(settlement).apply(payment,false,"VNPAY_QUERY_FAILED");
  }
}
