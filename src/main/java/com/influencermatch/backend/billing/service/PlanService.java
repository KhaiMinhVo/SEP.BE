package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.controller.*;
import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.dto.PlanDTOs;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

  private final PlanRepository planRepository;

  @Transactional
  public PlanDTOs.PlanResponse createPlan(PlanDTOs.PlanCreateRequest request) {
    Plan plan =
        Plan.builder()
            .planName(request.getPlanName())
            .price(request.getPrice())
            .currency(request.getCurrency())
            .durationDays(request.getDurationDays())
            .campaignQuota(request.getCampaignQuota())
            .recommendationQuota(request.getRecommendationQuota())
            .refreshQuota(request.getRefreshQuota())
            .description(request.getDescription())
            .status(PlanStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .build();

    Plan savedPlan = planRepository.save(plan);
    log.info("Created new plan: {}", savedPlan.getId());
    return toResponse(savedPlan);
  }

  @Transactional(readOnly = true)
  public Page<PlanDTOs.PlanResponse> getAllPlans(Pageable pageable) {
    return planRepository.findAll(pageable).map(this::toResponse);
  }

  @Transactional(readOnly = true)
  public PlanDTOs.PlanResponse getPlanById(UUID id) {
    Plan plan = findPlanEntityById(id);
    return toResponse(plan);
  }

  @Transactional
  public PlanDTOs.PlanResponse updatePlan(UUID id, PlanDTOs.PlanUpdateRequest request) {
    Plan plan = findPlanEntityById(id);

    if (request.getPlanName() != null) plan.setPlanName(request.getPlanName());
    if (request.getPrice() != null) plan.setPrice(request.getPrice());
    if (request.getCurrency() != null) plan.setCurrency(request.getCurrency());
    if (request.getDurationDays() != null) plan.setDurationDays(request.getDurationDays());
    if (request.getCampaignQuota() != null) plan.setCampaignQuota(request.getCampaignQuota());
    if (request.getRecommendationQuota() != null)
      plan.setRecommendationQuota(request.getRecommendationQuota());
    if (request.getRefreshQuota() != null) plan.setRefreshQuota(request.getRefreshQuota());
    if (request.getDescription() != null) plan.setDescription(request.getDescription());
    if (request.getStatus() != null) plan.setStatus(request.getStatus());

    Plan updatedPlan = planRepository.save(plan);
    log.info("Updated plan: {}", id);
    return toResponse(updatedPlan);
  }

  @Transactional
  public void deactivatePlan(UUID id) {
    Plan plan = findPlanEntityById(id);
    plan.setStatus(PlanStatus.INACTIVE);
    planRepository.save(plan);
    log.info("Deactivated plan: {}", id);
  }

  public Plan findPlanEntityById(UUID id) {
    return planRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", id));
  }

  public PlanDTOs.PlanResponse toResponse(Plan plan) {
    return PlanDTOs.PlanResponse.builder()
        .id(plan.getId())
        .planName(plan.getPlanName())
        .price(plan.getPrice())
        .currency(plan.getCurrency())
        .durationDays(plan.getDurationDays())
        .campaignQuota(plan.getCampaignQuota())
        .recommendationQuota(plan.getRecommendationQuota())
        .refreshQuota(plan.getRefreshQuota())
        .description(plan.getDescription())
        .status(plan.getStatus())
        .createdAt(plan.getCreatedAt())
        .build();
  }
}
