package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.PaymentSettlementService;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentSettlementServiceTest {
  final BrandProfileRepository brands=mock(BrandProfileRepository.class);
  final PaymentRepository payments=mock(PaymentRepository.class);
  final SubscriptionRepository subscriptions=mock(SubscriptionRepository.class);
  final EntityManager em=mock(EntityManager.class);
  final PaymentSettlementService service=new PaymentSettlementService(brands,payments,subscriptions,em);
  Payment payment() {
    var brand=BrandProfile.builder().id(UUID.randomUUID()).build();
    var sub=Subscription.builder().id(UUID.randomUUID()).brandProfile(brand).plan(Plan.builder().durationDays(30).build())
        .status(SubscriptionStatus.PENDING).startDate(LocalDate.of(2020,1,1)).expirationDate(LocalDate.of(2020,1,31))
        .usedCampaignQuota(2).usedRecommendationQuota(3).usedRefreshQuota(4).build();
    return Payment.builder().id(UUID.randomUUID()).brandProfile(brand).subscription(sub)
        .transactionCode(UUID.randomUUID().toString()).status(PaymentStatus.PENDING).build();
  }
  @Test void locksBrandBeforePaymentAndRefreshesLockedEntity() {
    var p=payment();when(brands.lockForBilling(p.getBrandProfile().getId())).thenReturn(Optional.of(p.getBrandProfile()));
    when(payments.findLockedByTransactionCode(p.getTransactionCode())).thenReturn(Optional.of(p));
    assertThat(service.lock(p)).isSameAs(p);
    var order=inOrder(brands,payments,em);order.verify(brands).lockForBilling(p.getBrandProfile().getId());
    order.verify(payments).findLockedByTransactionCode(p.getTransactionCode());order.verify(em).refresh(p);
  }
  @Test void successUsesActivationDateAndPreservesQuota() {
    var p=payment();var before=LocalDate.now();
    assertThat(service.apply(p,true,null)).isTrue();var sub=p.getSubscription();
    assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);assertThat(p.getPaidAt()).isNotNull();
    assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);assertThat(sub.getStartDate()).isBetween(before,LocalDate.now());
    assertThat(sub.getExpirationDate()).isEqualTo(sub.getStartDate().plusDays(30));
    assertThat(sub.getUsedCampaignQuota()).isEqualTo(2);assertThat(sub.getUsedRecommendationQuota()).isEqualTo(3);
    assertThat(sub.getUsedRefreshQuota()).isEqualTo(4);verify(payments).save(p);verify(subscriptions).save(sub);
  }
  @Test void failureCancelsWithoutChangingDatesOrQuota() {
    var p=payment();var start=p.getSubscription().getStartDate();var expiration=p.getSubscription().getExpirationDate();
    assertThat(service.apply(p,false,"DECLINED")).isTrue();assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(p.getFailureReason()).isEqualTo("DECLINED");assertThat(p.getPaidAt()).isNull();
    assertThat(p.getSubscription().getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    assertThat(p.getSubscription().getStartDate()).isEqualTo(start);assertThat(p.getSubscription().getExpirationDate()).isEqualTo(expiration);
    assertThat(p.getSubscription().getUsedCampaignQuota()).isEqualTo(2);
  }
  @ParameterizedTest @CsvSource({"PAID,true,true","PAID,false,false","FAILED,false,true","FAILED,true,false","REFUNDED,true,false","REFUNDED,false,false"})
  void terminalStatesNeverRewriteDatesOrQuota(PaymentStatus status,boolean success,boolean expected) {
    var p=payment();p.setStatus(status);var sub=p.getSubscription();var start=sub.getStartDate();
    assertThat(service.apply(p,success,"ignored")).isEqualTo(expected);
    assertThat(p.getStatus()).isEqualTo(status);assertThat(sub.getStartDate()).isEqualTo(start);
    assertThat(sub.getUsedCampaignQuota()).isEqualTo(2);verifyNoInteractions(payments,subscriptions,em);
  }
  @Test void activeSubscriptionCancelsOldActiveSubscriptionsAndActivatesNew() {
    var p=payment();
    var oldSub=Subscription.builder().id(UUID.randomUUID()).status(SubscriptionStatus.ACTIVE).build();
    when(subscriptions.findByBrandProfileIdAndStatus(eq(p.getBrandProfile().getId()), eq(SubscriptionStatus.ACTIVE)))
        .thenReturn(List.of(oldSub));
    assertThat(service.apply(p,true,null)).isTrue();
    assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(oldSub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    assertThat(p.getSubscription().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    verify(subscriptions).save(oldSub);
    verify(subscriptions).save(p.getSubscription());
  }
  @Test void nonPendingSubscriptionCannotBeActivated() {
    var p=payment();p.getSubscription().setStatus(SubscriptionStatus.CANCELLED);
    assertThat(service.apply(p,true,null)).isFalse();verifyNoInteractions(payments,subscriptions);
  }
}
