package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.enums.SubscriptionStatus;
import com.influencermatch.backend.billing.model.Plan;
import com.influencermatch.backend.billing.model.Subscription;
import com.influencermatch.backend.billing.repository.PlanRepository;
import com.influencermatch.backend.billing.repository.SubscriptionRepository;
import com.influencermatch.backend.billing.service.PlanService;
import com.influencermatch.backend.billing.service.SubscriptionService;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ForbiddenException;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuotaReservationPhase1Test {

  private SubscriptionRepository subscriptionRepository;
  private PlanService planService;
  private PlanRepository planRepository;
  private BrandProfileRepository brandProfileRepository;
  private AuditService auditService;
  private EntityManager entityManager;
  private SubscriptionService subscriptionService;

  private UUID brandProfileId;
  private UUID subscriptionId;
  private Plan plan;
  private Subscription subscription;

  @BeforeEach
  void setUp() {
    subscriptionRepository = mock(SubscriptionRepository.class);
    planService = mock(PlanService.class);
    planRepository = mock(PlanRepository.class);
    brandProfileRepository = mock(BrandProfileRepository.class);
    auditService = mock(AuditService.class);
    entityManager = mock(EntityManager.class);

    subscriptionService = new SubscriptionService(
        subscriptionRepository,
        planService,
        planRepository,
        brandProfileRepository,
        auditService,
        entityManager
    );

    brandProfileId = UUID.randomUUID();
    subscriptionId = UUID.randomUUID();

    plan = Plan.builder()
        .id(UUID.randomUUID())
        .planName("Pro Tier")
        .campaignQuota(10)
        .recommendationQuota(50)
        .build();

    subscription = Subscription.builder()
        .id(subscriptionId)
        .brandProfile(BrandProfile.builder().id(brandProfileId).build())
        .plan(plan)
        .status(SubscriptionStatus.ACTIVE)
        .startDate(LocalDate.now().minusDays(5))
        .expirationDate(LocalDate.now().plusDays(25))
        .usedRecommendationQuota(10)
        .build();
  }

  @Test
  @DisplayName("TEST-01: reserveRecommendationQuota succeeds and returns subscription UUID when quota available")
  void testReserveQuota_success() {
    when(subscriptionRepository.findByBrandProfileId(brandProfileId))
        .thenReturn(List.of(subscription));
    when(subscriptionRepository.reserveQuotaAtomically(subscriptionId))
        .thenReturn(1);

    UUID reservedId = subscriptionService.reserveRecommendationQuota(brandProfileId);

    assertThat(reservedId).isEqualTo(subscriptionId);
    verify(subscriptionRepository).reserveQuotaAtomically(subscriptionId);
  }

  @Test
  @DisplayName("TEST-01: reserveRecommendationQuota throws QUOTA_EXCEEDED when atomic update returns 0")
  void testReserveQuota_exceeded() {
    when(subscriptionRepository.findByBrandProfileId(brandProfileId))
        .thenReturn(List.of(subscription));
    when(subscriptionRepository.reserveQuotaAtomically(subscriptionId))
        .thenReturn(0);

    assertThatThrownBy(() -> subscriptionService.reserveRecommendationQuota(brandProfileId))
        .isInstanceOf(ConflictException.class)
        .matches(e -> ((ConflictException) e).code() == ErrorCode.QUOTA_EXCEEDED);

    verify(subscriptionRepository).reserveQuotaAtomically(subscriptionId);
  }

  @Test
  @DisplayName("TEST-02: releaseRecommendationQuota calls repository releaseQuotaReservation to rollback quota")
  void testReleaseQuota_rollback() {
    subscriptionService.releaseRecommendationQuota(subscriptionId);

    verify(subscriptionRepository).releaseQuotaReservation(subscriptionId);
  }

  @Test
  @DisplayName("TEST-02: releaseRecommendationQuota ignores null subscriptionId without errors")
  void testReleaseQuota_nullIgnored() {
    subscriptionService.releaseRecommendationQuota(null);

    verify(subscriptionRepository, never()).releaseQuotaReservation(any());
  }

  @Test
  @DisplayName("Throws ForbiddenException when brand has no active subscription and plans exist")
  void testReserveQuota_noActiveSubscription() {
    when(subscriptionRepository.findByBrandProfileId(brandProfileId))
        .thenReturn(List.of());
    when(planRepository.count()).thenReturn(2L);

    assertThatThrownBy(() -> subscriptionService.reserveRecommendationQuota(brandProfileId))
        .isInstanceOf(ForbiddenException.class);
  }
}
