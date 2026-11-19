package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.dto.PlanDTOs;
import com.influencermatch.backend.billing.enums.PlanStatus;
import com.influencermatch.backend.billing.model.Plan;
import com.influencermatch.backend.billing.repository.PlanRepository;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan catalog. Edits and deactivation only affect future purchases: every subscription captures
 * the plan terms (name, price, duration, quotas) when it is created, and quota guards, renewal
 * settlement and subscription responses read that captured contract instead of the live catalog.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

  private final PlanRepository planRepository;
  private final AuditService audit;

  @Transactional
  @PreAuthorize("hasAuthority('MANAGE_PLAN')")
  public PlanDTOs.PlanResponse createPlan(PlanDTOs.PlanCreateRequest request) {
    Permissions.require(Permission.MANAGE_PLAN);
    String name = request.getPlanName().strip();
    if (planRepository.existsByPlanNameIgnoreCase(name)) throw duplicateName(name);
    Plan plan =
        Plan.builder()
            .planName(name)
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

    Plan savedPlan = saveUnique(plan);
    audit.record(
        "CREATE_PLAN", "Plan", savedPlan.getId(), null, toResponse(savedPlan), "Tạo mới gói cước dịch vụ");
    log.info("Created new plan: {}", savedPlan.getId());
    return withActiveCount(savedPlan, 0L);
  }

  @Transactional(readOnly = true)
  @PreAuthorize("hasAuthority('VIEW_PLAN') or hasAuthority('MANAGE_PLAN')")
  public Page<PlanDTOs.PlanResponse> getAllPlans(Pageable pageable) {
    Permissions.require(Permission.VIEW_PLAN, Permission.MANAGE_PLAN);
    if (!Permissions.has(Permission.MANAGE_PLAN))
      return planRepository.findByStatus(PlanStatus.ACTIVE, pageable).map(this::toResponse);
    Page<Plan> page = planRepository.findAll(pageable);
    Map<UUID, Long> counts = activeCounts(page.map(Plan::getId).getContent());
    return page.map(p -> withActiveCount(p, counts.getOrDefault(p.getId(), 0L)));
  }

  @Transactional(readOnly = true)
  @PreAuthorize("hasAuthority('VIEW_PLAN') or hasAuthority('MANAGE_PLAN')")
  public PlanDTOs.PlanResponse getPlanById(UUID id) {
    Permissions.require(Permission.VIEW_PLAN, Permission.MANAGE_PLAN);
    Plan plan = findPlanEntityById(id);
    if (!Permissions.has(Permission.MANAGE_PLAN)) {
      if (plan.getStatus() != PlanStatus.ACTIVE) throw new ResourceNotFoundException("Plan", "id", id);
      return toResponse(plan);
    }
    return withActiveCount(plan, activeCount(id));
  }

  @Transactional
  @PreAuthorize("hasAuthority('MANAGE_PLAN')")
  public PlanDTOs.PlanResponse updatePlan(UUID id, PlanDTOs.PlanUpdateRequest request) {
    Permissions.require(Permission.MANAGE_PLAN);
    Plan plan = findPlanEntityById(id);
    var before = toResponse(plan);

    String name = request.getPlanName() == null ? null : request.getPlanName().strip();
    if (name != null
        && !name.equalsIgnoreCase(plan.getPlanName())
        && planRepository.existsByPlanNameIgnoreCaseAndIdNot(name, id)) throw duplicateName(name);

    boolean changed = false;
    if (name != null && !name.equals(plan.getPlanName())) {
      plan.setPlanName(name);
      changed = true;
    }
    if (request.getPrice() != null
        && (plan.getPrice() == null || request.getPrice().compareTo(plan.getPrice()) != 0)) {
      plan.setPrice(request.getPrice());
      changed = true;
    }
    if (request.getCurrency() != null && !request.getCurrency().equals(plan.getCurrency())) {
      plan.setCurrency(request.getCurrency());
      changed = true;
    }
    if (request.getDurationDays() != null && request.getDurationDays() != plan.getDurationDays()) {
      plan.setDurationDays(request.getDurationDays());
      changed = true;
    }
    if (request.getCampaignQuota() != null && request.getCampaignQuota() != plan.getCampaignQuota()) {
      plan.setCampaignQuota(request.getCampaignQuota());
      changed = true;
    }
    if (request.getRecommendationQuota() != null
        && request.getRecommendationQuota() != plan.getRecommendationQuota()) {
      plan.setRecommendationQuota(request.getRecommendationQuota());
      changed = true;
    }
    if (request.getRefreshQuota() != null && request.getRefreshQuota() != plan.getRefreshQuota()) {
      plan.setRefreshQuota(request.getRefreshQuota());
      changed = true;
    }
    if (request.getDescription() != null
        && !Objects.equals(request.getDescription(), plan.getDescription())) {
      plan.setDescription(request.getDescription());
      changed = true;
    }
    if (request.getStatus() != null && request.getStatus() != plan.getStatus()) {
      plan.setStatus(request.getStatus());
      changed = true;
    }

    long active = activeCount(id);
    if (!changed) return withActiveCount(plan, active);

    Plan updatedPlan = saveUnique(plan);
    audit.record(
        "UPDATE_PLAN",
        "Plan",
        id,
        before,
        toResponse(updatedPlan),
        "Cập nhật gói cước dịch vụ; " + impactNote(active));
    log.info("Updated plan {} ({} active subscriptions keep purchased terms)", id, active);
    return withActiveCount(updatedPlan, active);
  }

  /** Idempotent: deactivating an INACTIVE plan is a no-op and is not audited again. */
  @Transactional
  @PreAuthorize("hasAuthority('MANAGE_PLAN')")
  public void deactivatePlan(UUID id) {
    Permissions.require(Permission.MANAGE_PLAN);
    Plan plan = findPlanEntityById(id);
    if (plan.getStatus() == PlanStatus.INACTIVE) return;
    var before = toResponse(plan);
    plan.setStatus(PlanStatus.INACTIVE);
    planRepository.save(plan);
    long active = activeCount(id);
    audit.record(
        "DEACTIVATE_PLAN",
        "Plan",
        id,
        before,
        toResponse(plan),
        "Ngừng bán gói cước dịch vụ; " + impactNote(active));
    log.info("Deactivated plan {} ({} active subscriptions run until expiration)", id, active);
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

  private PlanDTOs.PlanResponse withActiveCount(Plan plan, long activeSubscriptions) {
    var response = toResponse(plan);
    response.setActiveSubscriptionCount(activeSubscriptions);
    return response;
  }

  private long activeCount(UUID planId) {
    return activeCounts(List.of(planId)).getOrDefault(planId, 0L);
  }

  private Map<UUID, Long> activeCounts(List<UUID> planIds) {
    Map<UUID, Long> counts = new HashMap<>();
    if (planIds.isEmpty()) return counts;
    for (Object[] row : planRepository.countActiveSubscriptionsByPlan(planIds))
      counts.put((UUID) row[0], ((Number) row[1]).longValue());
    return counts;
  }

  private static String impactNote(long activeSubscriptions) {
    return activeSubscriptions
        + " subscription đang hoạt động giữ nguyên điều khoản đã mua; thay đổi chỉ áp dụng cho lượt mua mới";
  }

  private Plan saveUnique(Plan plan) {
    try {
      return planRepository.saveAndFlush(plan);
    } catch (DataIntegrityViolationException e) {
      Throwable cause = e;
      while (cause != null) {
        if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
          String constraint = violation.getConstraintName();
          if (java.util.Set.of("plan_plan_name_key", "ux_plan_name", "ux_plan_name_normalized")
              .contains(constraint == null ? "" : constraint)) throw duplicateName(plan.getPlanName());
        }
        cause = cause.getCause();
      }
      throw e;
    }
  }

  private static ConflictException duplicateName(String name) {
    return new ConflictException(
        ErrorCode.RESOURCE_ALREADY_EXISTS, "A plan named '" + name + "' already exists");
  }
}
