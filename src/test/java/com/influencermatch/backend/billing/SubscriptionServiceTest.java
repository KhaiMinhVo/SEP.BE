package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.dto.SubscriptionDTOs.SubscriptionCreateRequest;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.PlanRepository;
import com.influencermatch.backend.billing.repository.SubscriptionRepository;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;

class SubscriptionServiceTest {
  final SubscriptionRepository subscriptions=mock(SubscriptionRepository.class);
  final PlanService plans=mock(PlanService.class);
  final BrandProfileRepository brands=mock(BrandProfileRepository.class);
  final AuditService audit=mock(AuditService.class);
  final PlanRepository planRepository=mock(PlanRepository.class);
  final EntityManager em=mock(EntityManager.class);
  final SubscriptionService service=new SubscriptionService(subscriptions,plans,planRepository,brands,audit,em);
  final com.influencermatch.backend.user.model.User owner=TestActors.user(Role.BRAND);
  final BrandProfile brand=BrandProfile.builder().id(UUID.randomUUID()).user(owner).build();
  final Plan plan=Plan.builder().id(UUID.randomUUID()).status(PlanStatus.ACTIVE).price(BigDecimal.ZERO).durationDays(30).build();
  @BeforeEach void login(){TestActors.authenticate(owner);}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  SubscriptionCreateRequest request(){return SubscriptionCreateRequest.builder().brandId(brand.getId()).planId(plan.getId()).build();}
  void lookup(){when(brands.lockForBilling(brand.getId())).thenReturn(Optional.of(brand));when(plans.findPlanEntityById(plan.getId())).thenReturn(plan);}
  @Test void freePlanInitializesDatesAndZeroQuota(){
    lookup();when(subscriptions.save(any())).thenAnswer(i->i.getArgument(0));
    var result=service.subscribeBrandToPlan(request());assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(result.getExpirationDate()).isEqualTo(result.getStartDate().plusDays(30));
    assertThat(result.getUsedCampaignQuota()).isZero();assertThat(result.getUsedRecommendationQuota()).isZero();assertThat(result.getUsedRefreshQuota()).isZero();
    verifyNoInteractions(audit);
  }
  @Test void paidPlanCannotBeActivatedDirectlyByBrand(){
    lookup();plan.setPrice(BigDecimal.ONE);
    assertThatThrownBy(()->service.subscribeBrandToPlan(request())).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.PAID_PLAN_REQUIRES_PAYMENT));verifyNoInteractions(subscriptions,audit);
  }
  @Test void inactivePlanAndExistingActiveSubscriptionAreConflicts(){
    lookup();plan.setStatus(PlanStatus.INACTIVE);
    assertThatThrownBy(()->service.subscribeBrandToPlan(request())).isInstanceOf(BusinessException.class);
    verify(subscriptions,never()).save(any());plan.setStatus(PlanStatus.ACTIVE);
    when(subscriptions.existsByBrandProfileIdAndStatusAndExpirationDateGreaterThanEqual(eq(brand.getId()),eq(SubscriptionStatus.ACTIVE),any())).thenReturn(true);
    assertThatThrownBy(()->service.subscribeBrandToPlan(request())).isInstanceOf(BusinessException.class);verify(subscriptions,never()).save(any());verifyNoInteractions(audit);
  }
  @Test void foreignBrandCannotReadSubscriptions(){
    brand.setUser(TestActors.user(Role.BRAND));when(brands.findById(brand.getId())).thenReturn(Optional.of(brand));
    assertThatThrownBy(()->service.getSubscriptionsForBrand(brand.getId())).isInstanceOf(ForbiddenException.class);verifyNoInteractions(subscriptions,audit);
  }
  @Test void cancellationPreservesQuotaAndAdminNoopDoesNotAudit(){
    TestActors.authenticate(TestActors.user(Role.ADMIN));var sub=Subscription.builder().id(UUID.randomUUID()).brandProfile(brand).plan(plan)
        .status(SubscriptionStatus.ACTIVE).startDate(LocalDate.of(2026,1,1)).expirationDate(LocalDate.of(2026,1,31)).usedCampaignQuota(2).build();
    when(subscriptions.findById(sub.getId())).thenReturn(Optional.of(sub));when(brands.lockForBilling(brand.getId())).thenReturn(Optional.of(brand));
    service.cancelSubscription(sub.getId());service.cancelSubscription(sub.getId());
    assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);assertThat(sub.getUsedCampaignQuota()).isEqualTo(2);
    assertThat(sub.getExpirationDate()).isEqualTo(LocalDate.of(2026,1,31));
    verify(audit,times(1)).record(eq("CANCEL_SUBSCRIPTION"),anyString(),eq(sub.getId()),any(),any(),anyString());
  }
  @Test void checkAndDeductCampaignQuotaSuccessAndExceeded(){
    var activePlan = Plan.builder().id(UUID.randomUUID()).campaignQuota(2).build();
    var sub = Subscription.builder().id(UUID.randomUUID()).brandProfile(brand).plan(activePlan)
        .status(SubscriptionStatus.ACTIVE).expirationDate(LocalDate.now().plusDays(10)).usedCampaignQuota(1).build();
    when(subscriptions.findByBrandProfileId(brand.getId())).thenReturn(List.of(sub));
    service.checkAndDeductCampaignQuota(brand.getId());
    assertThat(sub.getUsedCampaignQuota()).isEqualTo(2);
    verify(subscriptions).save(sub);

    assertThatThrownBy(() -> service.checkAndDeductCampaignQuota(brand.getId()))
        .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED));
  }
}
