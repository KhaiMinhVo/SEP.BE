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

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanController extends BaseController {

  private final PlanService planService;

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<ApiResponse<PlanDTOs.PlanResponse>> createPlan(
      @Valid @RequestBody PlanDTOs.PlanCreateRequest request) {
    PlanDTOs.PlanResponse response = planService.createPlan(request);
    return created("Plan created successfully", response);
  }

  @GetMapping
  public ResponseEntity<ApiResponse<PageResponse<PlanDTOs.PlanResponse>>> getAllPlans(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String direction) {

    Pageable pageable = buildPageable(page, size, sortBy, direction);
    Page<PlanDTOs.PlanResponse> planPage = planService.getAllPlans(pageable);
    return pagedOk("Plans retrieved successfully", planPage);
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<PlanDTOs.PlanResponse>> getPlanById(@PathVariable UUID id) {
    return ok("Plan retrieved successfully", planService.getPlanById(id));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<ApiResponse<PlanDTOs.PlanResponse>> updatePlan(
      @PathVariable UUID id, @Valid @RequestBody PlanDTOs.PlanUpdateRequest request) {
    return ok("Plan updated successfully", planService.updatePlan(id, request));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<ApiResponse<Void>> deactivatePlan(@PathVariable UUID id) {
    planService.deactivatePlan(id);
    return ok("Plan deactivated successfully");
  }
}
