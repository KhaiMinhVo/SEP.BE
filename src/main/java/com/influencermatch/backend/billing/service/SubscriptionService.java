package com.influencermatch.backend.billing.service;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.controller.*;

import com.influencermatch.backend.billing.dto.SubscriptionDTOs;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanService planService;
    private final BrandProfileRepository brandProfileRepository;

    @Transactional
    public SubscriptionDTOs.SubscriptionResponse subscribeBrandToPlan(SubscriptionDTOs.SubscriptionCreateRequest request) {
        BrandProfile brandProfile = brandProfileRepository.findById(request.getBrandId())
                .orElseThrow(() -> new ResourceNotFoundException("BrandProfile", "id", request.getBrandId()));

        Plan plan = planService.findPlanEntityById(request.getPlanId());

        Subscription subscription = Subscription.builder()
                .brandProfile(brandProfile)
                .plan(plan)
                .startDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(plan.getDurationDays()))
                .status(SubscriptionStatus.ACTIVE)
                .usedCampaignQuota(0)
                .usedRecommendationQuota(0)
                .usedRefreshQuota(0)
                .build();

        Subscription savedSubscription = subscriptionRepository.save(subscription);
        log.info("Created subscription {} for brand {}", savedSubscription.getId(), brandProfile.getId());
        return toResponse(savedSubscription);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionDTOs.SubscriptionResponse> getSubscriptionsForBrand(UUID brandId) {
        return subscriptionRepository.findByBrandProfileId(brandId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void cancelSubscription(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", "id", subscriptionId));
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscriptionRepository.save(subscription);
        log.info("Cancelled subscription: {}", subscriptionId);
    }

    public SubscriptionDTOs.SubscriptionResponse toResponse(Subscription subscription) {
        return SubscriptionDTOs.SubscriptionResponse.builder()
                .id(subscription.getId())
                .brandId(subscription.getBrandProfile().getId())
                .plan(planService.toResponse(subscription.getPlan()))
                .startDate(subscription.getStartDate())
                .expirationDate(subscription.getExpirationDate())
                .status(subscription.getStatus())
                .usedCampaignQuota(subscription.getUsedCampaignQuota())
                .usedRecommendationQuota(subscription.getUsedRecommendationQuota())
                .usedRefreshQuota(subscription.getUsedRefreshQuota())
                .build();
    }
}
