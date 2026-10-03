package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.dto.SubscriptionDTOs;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.*;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionService {
  private final SubscriptionRepository subscriptionRepository;
  private final PlanService planService;
  private final BrandProfileRepository brandProfileRepository;
  private final AuditService audit;
  private final jakarta.persistence.EntityManager entityManager;

  @Transactional
  public SubscriptionDTOs.SubscriptionResponse subscribeBrandToPlan(
      SubscriptionDTOs.SubscriptionCreateRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SUBSCRIPTION, Permission.MANAGE_SUBSCRIPTION);
    BrandProfile brand =
        brandProfileRepository
            .lockForBilling(request.getBrandId())
            .orElseThrow(
                () -> new ResourceNotFoundException("BrandProfile", "id", request.getBrandId()));
    requireOwnerOrAdmin(brand);
    Plan plan = planService.findPlanEntityById(request.getPlanId());
    if (plan.getStatus() != PlanStatus.ACTIVE)
      throw new ConflictException(ErrorCode.SUBSCRIPTION_CONFLICT, "Plan is inactive");
    if (!Permissions.has(Permission.MANAGE_SUBSCRIPTION) && plan.getPrice().signum() > 0)
      throw new ConflictException(
          ErrorCode.PAID_PLAN_REQUIRES_PAYMENT, "Use /billing/subscribe for paid plans");
    if (subscriptionRepository.existsByBrandProfileIdAndStatusAndExpirationDateGreaterThanEqual(
        brand.getId(), SubscriptionStatus.ACTIVE, LocalDate.now()))
      throw new ConflictException(
          ErrorCode.SUBSCRIPTION_CONFLICT, "An active subscription already exists");
    Subscription sub =
        subscriptionRepository.save(
            Subscription.builder()
                .brandProfile(brand)
                .plan(plan)
                .startDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(plan.getDurationDays()))
                .status(SubscriptionStatus.ACTIVE)
                .usedCampaignQuota(0)
                .usedRecommendationQuota(0)
                .usedRefreshQuota(0)
                .build());
    if (Permissions.has(Permission.MANAGE_SUBSCRIPTION))
      audit.record(
          "CREATE_SUBSCRIPTION",
          "Subscription",
          sub.getId(),
          null,
          toResponse(sub),
          "Administrative subscription creation");
    return toResponse(sub);
  }

  @Transactional(readOnly = true)
  public List<SubscriptionDTOs.SubscriptionResponse> getSubscriptionsForBrand(UUID brandId) {
    Permissions.require(Permission.MANAGE_OWN_SUBSCRIPTION, Permission.MANAGE_SUBSCRIPTION);
    var brand =
        brandProfileRepository
            .findById(brandId)
            .orElseThrow(() -> new ResourceNotFoundException("BrandProfile", "id", brandId));
    requireOwnerOrAdmin(brand);
    return subscriptionRepository.findByBrandProfileId(brandId).stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional
  public void cancelSubscription(UUID id) {
    Permissions.require(Permission.MANAGE_OWN_SUBSCRIPTION, Permission.MANAGE_SUBSCRIPTION);
    var sub =
        subscriptionRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Subscription", "id", id));
    requireOwnerOrAdmin(sub.getBrandProfile());
    brandProfileRepository.lockForBilling(sub.getBrandProfile().getId()).orElseThrow();
    entityManager.refresh(sub);
    var before = toResponse(sub);
    if (sub.getStatus() == SubscriptionStatus.CANCELLED) return;
    sub.setStatus(SubscriptionStatus.CANCELLED);
    if (Permissions.has(Permission.MANAGE_SUBSCRIPTION))
      audit.record(
          "CANCEL_SUBSCRIPTION",
          "Subscription",
          id,
          before,
          toResponse(sub),
          "Administrative cancellation");
  }

  private void requireOwnerOrAdmin(BrandProfile brand) {
    if (!Permissions.has(Permission.MANAGE_SUBSCRIPTION)
        && !brand.getUser().getId().equals(Permissions.actor().getId()))
      throw new ForbiddenException("Subscription belongs to another brand");
  }

  public SubscriptionDTOs.SubscriptionResponse toResponse(Subscription sub) {
    return SubscriptionDTOs.SubscriptionResponse.builder()
        .id(sub.getId())
        .brandId(sub.getBrandProfile().getId())
        .plan(planService.toResponse(sub.getPlan()))
        .startDate(sub.getStartDate())
        .expirationDate(sub.getExpirationDate())
        .status(sub.getStatus())
        .usedCampaignQuota(sub.getUsedCampaignQuota())
        .usedRecommendationQuota(sub.getUsedRecommendationQuota())
        .usedRefreshQuota(sub.getUsedRefreshQuota())
        .build();
  }
}
