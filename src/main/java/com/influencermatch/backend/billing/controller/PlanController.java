package com.influencermatch.backend.billing.controller;

import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.dto.PlanDTOs;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.common.BaseController;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.StandardDTOs.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/plans")
@RequiredArgsConstructor
@Tag(name = "Subscription Plans", description = "Subscription plans catalog (FREE, PRO, ENTERPRISE)")
@SecurityRequirement(name = "BearerAuth")
public class PlanController extends BaseController {

  private final PlanService planService;

  @Operation(summary = "Create a subscription plan (Admin only)")
  @PostMapping
  @PreAuthorize("hasAuthority('MANAGE_PLAN')")
  public ResponseEntity<ApiResponse<PlanDTOs.PlanResponse>> createPlan(
      @Valid @RequestBody PlanDTOs.PlanCreateRequest request) {
    PlanDTOs.PlanResponse response = planService.createPlan(request);
    return created("Plan created successfully", response);
  }

  @Operation(summary = "Get all subscription plans")
  @GetMapping
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_PLAN') or hasAuthority('MANAGE_PLAN')")
  public ResponseEntity<ApiResponse<PageResponse<PlanDTOs.PlanResponse>>> getAllPlans(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String direction) {

    Pageable pageable = buildPageable(page, size, sortBy, direction);
    Page<PlanDTOs.PlanResponse> planPage = planService.getAllPlans(pageable);
    return pagedOk("Plans retrieved successfully", planPage);
  }

  @Operation(summary = "Get subscription plan by ID")
  @GetMapping("/{id}")
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_PLAN') or hasAuthority('MANAGE_PLAN')")
  public ResponseEntity<ApiResponse<PlanDTOs.PlanResponse>> getPlanById(@PathVariable UUID id) {
    return ok("Plan retrieved successfully", planService.getPlanById(id));
  }

  @Operation(summary = "Update subscription plan (Admin only)")
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('MANAGE_PLAN')")
  public ResponseEntity<ApiResponse<PlanDTOs.PlanResponse>> updatePlan(
      @PathVariable UUID id, @Valid @RequestBody PlanDTOs.PlanUpdateRequest request) {
    return ok("Plan updated successfully", planService.updatePlan(id, request));
  }

  @Operation(summary = "Deactivate subscription plan (Admin only)")
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('MANAGE_PLAN')")
  public ResponseEntity<ApiResponse<Void>> deactivatePlan(@PathVariable UUID id) {
    planService.deactivatePlan(id);
    return ok("Plan deactivated successfully");
  }
}
