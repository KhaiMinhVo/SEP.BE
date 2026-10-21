package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.dto.MockPaymentDtos.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import jakarta.validation.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;

class MockPaymentServiceTest {
  final PaymentRepository payments=mock(PaymentRepository.class);
  final PaymentSettlementService settlement=mock(PaymentSettlementService.class);
  final AuditService audit=mock(AuditService.class);
  final ValidatorFactory factory=Validation.buildDefaultValidatorFactory();
  final MockPaymentService service=new MockPaymentService(payments,settlement,audit,factory.getValidator());
  final com.influencermatch.backend.user.model.User owner=TestActors.user(Role.BRAND);
  @BeforeEach void login(){TestActors.authenticate(owner);}
  @AfterEach void clear(){SecurityContextHolder.clearContext();factory.close();}
  Payment payment(){return Payment.builder().id(UUID.randomUUID()).transactionCode("TX")
      .brandProfile(BrandProfile.builder().user(owner).build()).status(PaymentStatus.PENDING)
      .subscription(Subscription.builder().id(UUID.randomUUID()).status(SubscriptionStatus.PENDING).build()).build();}
  @Test void foreignPaymentIsHiddenBeforeAcquiringLocks(){
    var p=payment();p.getBrandProfile().setUser(TestActors.user(Role.BRAND));when(payments.findByTransactionCode("TX")).thenReturn(Optional.of(p));
    assertThatThrownBy(()->service.process(new Request("TX",Outcome.SUCCESS))).isInstanceOf(NotFoundException.class);
    verifyNoInteractions(settlement,audit);assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
  }
  @Test void missingAndInvalidRequestsDoNotSettle(){
    assertThatThrownBy(()->service.process(new Request(" ",Outcome.SUCCESS))).isInstanceOf(ConstraintViolationException.class);
    verifyNoInteractions(payments,settlement,audit);
    assertThatThrownBy(()->service.process(new Request("missing",Outcome.SUCCESS))).isInstanceOf(NotFoundException.class);
    verifyNoInteractions(settlement,audit);
  }
  @Test void conflictingOutcomeDoesNotAudit(){
    var p=payment();when(payments.findByTransactionCode("TX")).thenReturn(Optional.of(p));when(settlement.lock(p)).thenReturn(p);
    assertThatThrownBy(()->service.process(new Request("TX",Outcome.SUCCESS))).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.SUBSCRIPTION_CONFLICT));verifyNoInteractions(audit);
  }
  @Test void successfulStateChangeAuditsOnceAndRepeatedOutcomeDoesNotAuditAgain(){
    var p=payment();when(payments.findByTransactionCode("TX")).thenReturn(Optional.of(p));when(settlement.lock(p)).thenReturn(p);
    when(settlement.apply(p,true,"MOCK_FAILED")).thenAnswer(i->{p.setStatus(PaymentStatus.PAID);p.getSubscription().setStatus(SubscriptionStatus.ACTIVE);return true;});
    assertThat(service.process(new Request("TX",Outcome.SUCCESS)).paymentStatus()).isEqualTo(PaymentStatus.PAID);
    service.process(new Request("TX",Outcome.SUCCESS));
    verify(audit,times(1)).record(eq("MOCK_PAYMENT_WEBHOOK"),eq("Payment"),eq(p.getId()),any(),any(),anyString());
  }
}
