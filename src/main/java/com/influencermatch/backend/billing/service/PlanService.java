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
  private final com.influencermatch.backend.audit.service.AuditService audit;

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('MANAGE_PLAN')")
  public PlanDTOs.PlanResponse createPlan(PlanDTOs.PlanCreateRequest request) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.MANAGE_PLAN);
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
    audit.record(
        "CREATE_PLAN", "Plan", savedPlan.getId(), null, toResponse(savedPlan), "Plan created");
    log.info("Created new plan: {}", savedPlan.getId());
    return toResponse(savedPlan);
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_PLAN') or hasAuthority('MANAGE_PLAN')")
  public Page<PlanDTOs.PlanResponse> getAllPlans(Pageable pageable) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.VIEW_PLAN,
        com.influencermatch.backend.security.Permission.MANAGE_PLAN);
    if (!com.influencermatch.backend.security.Permissions.has(
        com.influencermatch.backend.security.Permission.MANAGE_PLAN))
      return planRepository.findByStatus(PlanStatus.ACTIVE, pageable).map(this::toResponse);
    return planRepository.findAll(pageable).map(this::toResponse);
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_PLAN') or hasAuthority('MANAGE_PLAN')")
  public PlanDTOs.PlanResponse getPlanById(UUID id) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.VIEW_PLAN,
        com.influencermatch.backend.security.Permission.MANAGE_PLAN);
    Plan plan = findPlanEntityById(id);
    if (!com.influencermatch.backend.security.Permissions.has(
            com.influencermatch.backend.security.Permission.MANAGE_PLAN)
        && plan.getStatus() != PlanStatus.ACTIVE)
      throw new ResourceNotFoundException("Plan", "id", id);
    return toResponse(plan);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('MANAGE_PLAN')")
  public PlanDTOs.PlanResponse updatePlan(UUID id, PlanDTOs.PlanUpdateRequest request) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.MANAGE_PLAN);
    Plan plan = findPlanEntityById(id);

    var before = toResponse(plan);
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
    audit.record("UPDATE_PLAN", "Plan", id, before, toResponse(updatedPlan), "Plan updated");
    log.info("Updated plan: {}", id);
    return toResponse(updatedPlan);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('MANAGE_PLAN')")
  public void deactivatePlan(UUID id) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.MANAGE_PLAN);
    Plan plan = findPlanEntityById(id);
    var before = toResponse(plan);
    plan.setStatus(PlanStatus.INACTIVE);
    planRepository.save(plan);
    audit.record("DEACTIVATE_PLAN", "Plan", id, before, toResponse(plan), "Plan deactivated");
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
