package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.billing.utils.VnPayUtil;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.config.PayOsConfig;
import com.influencermatch.backend.config.VnPayConfig;
import vn.payos.exception.WebhookException;
import vn.payos.model.webhooks.WebhookData;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentCallbackServiceTest {
  final PaymentRepository payments=mock(PaymentRepository.class);
  final PaymentSettlementService settlement=mock(PaymentSettlementService.class);
  final VnPayConfig config=mock(VnPayConfig.class);
  final PayOsConfig payOsConfig=mock(PayOsConfig.class);
  final PayOsPaymentClient payOsPaymentClient=mock(PayOsPaymentClient.class);
  final PaymentService service=new PaymentService(mock(PlanRepository.class),mock(SubscriptionRepository.class),payments,config,
      payOsConfig,payOsPaymentClient,mock(BrandProfileRepository.class),settlement);
  Map<String,String> params() {
    var p=new HashMap<String,String>();p.put("vnp_TmnCode","TEST");p.put("vnp_TxnRef","TX");p.put("vnp_Amount","10000");
    p.put("vnp_ResponseCode","00");p.put("vnp_TransactionStatus","00");return p;
  }
  void sign(Map<String,String> p) {p.remove("vnp_SecureHash");p.put("vnp_SecureHash",VnPayUtil.hashAllFields(p,"test-secret"));}
  Payment stored(){var p=Payment.builder().amount(new BigDecimal("100")).build();
    when(payments.findByTransactionCode("TX")).thenReturn(Optional.of(p));when(settlement.lock(p)).thenReturn(p);return p;}
  @ParameterizedTest @ValueSource(strings={"missing","short","tampered","merchant"})
  void invalidSignatureOrMerchantNeverLocksPayment(String kind) {
    var p=params();when(config.getHashSecret()).thenReturn("test-secret");when(config.getTmnCode()).thenReturn("TEST");sign(p);
    switch(kind){case "missing"->p.remove("vnp_SecureHash");case "short"->p.put("vnp_SecureHash","bad");
      case "tampered"->p.put("vnp_Amount","20000");case "merchant"->{p.put("vnp_TmnCode","OTHER");sign(p);}}
    assertThat(service.processVnPayReturn(p)).isFalse();verifyNoInteractions(payments,settlement);
  }
  @Test void signedWrongAmountDoesNotSettle(){
    when(config.getHashSecret()).thenReturn("test-secret");when(config.getTmnCode()).thenReturn("TEST");
    var payment=stored();var p=params();p.put("vnp_Amount","20000");sign(p);
    assertThat(service.processVnPayReturn(p)).isFalse();verify(settlement,never()).apply(any(),anyBoolean(),any());
    assertThat(payment.getPaidAt()).isNull();
  }
  @Test void signedGatewayFailureUsesSharedSettlementAndUnknownTransactionIsRejected(){
    when(config.getHashSecret()).thenReturn("test-secret");when(config.getTmnCode()).thenReturn("TEST");
    var p=params();sign(p);assertThat(service.processVnPayReturn(p)).isFalse();verifyNoInteractions(settlement);
    var payment=stored();p.put("vnp_ResponseCode","24");p.put("vnp_TransactionStatus","02");sign(p);
    when(settlement.apply(payment,false,"24")).thenReturn(true);
    assertThat(service.processVnPayReturn(p)).isTrue();verify(settlement).apply(payment,false,"24");
  }
  @ParameterizedTest @ValueSource(strings={"01", "04", "05", "07", "", "99"})
  void uncertainGatewayOutcomeNeverFailsOrActivatesPayment(String outcome) {
    when(config.getHashSecret()).thenReturn("test-secret"); when(config.getTmnCode()).thenReturn("TEST");
    stored(); var p=params(); p.put("vnp_TransactionStatus",outcome); sign(p);
    assertThat(service.processVnPayCallback(p)).isEqualTo(PaymentService.CallbackResult.PENDING);
    verify(settlement,never()).apply(any(),anyBoolean(),any());
  }

  @Test void payOsWebhookWithInvalidSignatureNeverLooksUpOrSettlesPayment() {
    when(payOsPaymentClient.verifyWebhook("invalid")).thenThrow(new WebhookException("bad signature"));

    assertThat(service.processPayOsWebhook("invalid"))
        .isEqualTo(PaymentService.CallbackResult.INVALID_SIGNATURE);
    verifyNoInteractions(payments, settlement);
  }

  @Test void verifiedPayOsWebhookChecksProviderAndAmountBeforeSettlement() {
    var payment = Payment.builder()
        .paymentGateway("PAYOS")
        .amount(new BigDecimal("100"))
        .build();
    when(payments.findByTransactionCode("123")).thenReturn(Optional.of(payment));
    when(settlement.lock(payment)).thenReturn(payment);
    when(settlement.apply(payment, true, null)).thenReturn(true);
    when(payOsPaymentClient.verifyWebhook("signed"))
        .thenReturn(WebhookData.builder()
            .orderCode(123L)
            .amount(100L)
            .currency("VND")
            .code("00")
            .description("IM0000123")
            .accountNumber("123")
            .reference("reference")
            .transactionDateTime("2026-11-24 02:00:00")
            .paymentLinkId("link")
            .desc("success")
            .build());

    assertThat(service.processPayOsWebhook("signed"))
        .isEqualTo(PaymentService.CallbackResult.ACCEPTED);
    verify(settlement).apply(payment, true, null);
  }

  @Test void verifiedPayOsWebhookWithWrongAmountDoesNotSettlePayment() {
    var payment = Payment.builder()
        .paymentGateway("PAYOS")
        .amount(new BigDecimal("100"))
        .build();
    when(payments.findByTransactionCode("123")).thenReturn(Optional.of(payment));
    when(settlement.lock(payment)).thenReturn(payment);
    when(payOsPaymentClient.verifyWebhook("signed"))
        .thenReturn(WebhookData.builder()
            .orderCode(123L)
            .amount(101L)
            .currency("VND")
            .code("00")
            .description("IM0000123")
            .accountNumber("123")
            .reference("reference")
            .transactionDateTime("2026-11-24 02:00:00")
            .paymentLinkId("link")
            .desc("success")
            .build());

    assertThat(service.processPayOsWebhook("signed"))
        .isEqualTo(PaymentService.CallbackResult.INVALID_AMOUNT);
    verify(settlement, never()).apply(any(), anyBoolean(), any());
  }

  @Test void payOsBrowserReturnQueriesProviderAndSettlesBeforeRedirect() {
    var payment = Payment.builder()
        .transactionCode("123")
        .paymentGateway("PAYOS")
        .status(com.influencermatch.backend.billing.enums.PaymentStatus.PENDING)
        .amount(new BigDecimal("100"))
        .build();
    when(payments.findByTransactionCode("123")).thenReturn(Optional.of(payment));
    when(settlement.lock(payment)).thenReturn(payment);
    when(settlement.apply(payment, true, null)).thenReturn(true);
    var link = new vn.payos.model.v2.paymentRequests.PaymentLink();
    link.setId("payment-link");
    link.setOrderCode(123L);
    link.setAmount(100L);
    link.setAmountPaid(100L);
    link.setAmountRemaining(0L);
    link.setStatus(vn.payos.model.v2.paymentRequests.PaymentLinkStatus.PAID);
    when(payOsPaymentClient.getPaymentLink(123L)).thenReturn(link);

    assertThat(service.processPayOsReturn("123"))
        .isEqualTo(PaymentService.PayOsReturnResult.PAID);
    verify(settlement).apply(payment, true, null);
  }

  @Test void payOsBrowserReturnDoesNotTrustReturnUrlPaymentStatus() {
    var payment = Payment.builder()
        .transactionCode("123")
        .paymentGateway("PAYOS")
        .status(com.influencermatch.backend.billing.enums.PaymentStatus.PENDING)
        .amount(new BigDecimal("100"))
        .build();
    when(payments.findByTransactionCode("123")).thenReturn(Optional.of(payment));
    when(settlement.lock(payment)).thenReturn(payment);
    var link = new vn.payos.model.v2.paymentRequests.PaymentLink();
    link.setId("payment-link");
    link.setOrderCode(123L);
    link.setAmount(100L);
    link.setAmountPaid(0L);
    link.setAmountRemaining(100L);
    link.setStatus(vn.payos.model.v2.paymentRequests.PaymentLinkStatus.PENDING);
    when(payOsPaymentClient.getPaymentLink(123L)).thenReturn(link);

    assertThat(service.processPayOsReturn("123"))
        .isEqualTo(PaymentService.PayOsReturnResult.PENDING);
    verify(settlement, never()).apply(any(), anyBoolean(), any());
  }

  @Test void vnPayCallbackSuccessReturnsAcceptedAndCallsSettlement() {
    when(config.getHashSecret()).thenReturn("test-secret");
    when(config.getTmnCode()).thenReturn("TEST");
    var payment = stored();
    var p = params();
    sign(p);
    when(settlement.apply(payment, true, "00")).thenReturn(true);

    assertThat(service.processVnPayCallback(p)).isEqualTo(PaymentService.CallbackResult.ACCEPTED);
    verify(settlement).apply(payment, true, "00");
  }

  @Test void vnPayCallbackWith127CharSignatureReturnsInvalidSignature() {
    when(config.getHashSecret()).thenReturn("test-secret");
    when(config.getTmnCode()).thenReturn("TEST");
    var p = params();
    p.put("vnp_SecureHash", "a".repeat(127));

    assertThat(service.processVnPayCallback(p))
        .isEqualTo(PaymentService.CallbackResult.INVALID_SIGNATURE);
    verifyNoInteractions(payments, settlement);
  }

  @Test void vnPayCallbackMerchantMismatchReturnsInvalidMerchant() {
    when(config.getHashSecret()).thenReturn("test-secret");
    when(config.getTmnCode()).thenReturn("TEST");
    var p = params();
    p.put("vnp_TmnCode", "OTHER");
    sign(p);

    assertThat(service.processVnPayCallback(p))
        .isEqualTo(PaymentService.CallbackResult.INVALID_MERCHANT);
    verifyNoInteractions(payments, settlement);
  }

  @Test void vnPayCallbackAmountOffByOneGatewayUnitReturnsInvalidAmount() {
    when(config.getHashSecret()).thenReturn("test-secret");
    when(config.getTmnCode()).thenReturn("TEST");
    stored();
    var p = params();
    p.put("vnp_Amount", "10001");
    sign(p);

    assertThat(service.processVnPayCallback(p))
        .isEqualTo(PaymentService.CallbackResult.INVALID_AMOUNT);
    verify(settlement, never()).apply(any(), anyBoolean(), any());
  }

  @Test void vnPayCallbackWhenSettlementReturnsFalseReturnsConflict() {
    when(config.getHashSecret()).thenReturn("test-secret");
    when(config.getTmnCode()).thenReturn("TEST");
    var payment = stored();
    var p = params();
    sign(p);
    when(settlement.apply(payment, true, "00")).thenReturn(false);

    assertThat(service.processVnPayCallback(p)).isEqualTo(PaymentService.CallbackResult.CONFLICT);
  }
}
