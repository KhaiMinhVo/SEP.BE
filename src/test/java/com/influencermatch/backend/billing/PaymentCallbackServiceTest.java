package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.billing.utils.VnPayUtil;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.config.VnPayConfig;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentCallbackServiceTest {
  final PaymentRepository payments=mock(PaymentRepository.class);
  final PaymentSettlementService settlement=mock(PaymentSettlementService.class);
  final VnPayConfig config=mock(VnPayConfig.class);
  final PaymentService service=new PaymentService(mock(PlanRepository.class),mock(SubscriptionRepository.class),payments,config,
      mock(BrandProfileRepository.class),settlement);
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
}
