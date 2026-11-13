package com.influencermatch.backend.billing.controller;

import com.influencermatch.backend.billing.dto.SubscriptionDTOs;
import com.influencermatch.backend.billing.service.SubscriptionService;
import com.influencermatch.backend.admin.dto.AdminAnalyticsDtos.SubscriptionSummary;
import com.influencermatch.backend.admin.service.AdminAnalyticsService;
import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/subscriptions")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_SUBSCRIPTION')")
@Tag(name = "Admin Subscriptions", description = "MANAGE_SUBSCRIPTION; changes are audited")
public class AdminSubscriptionController {
  private final SubscriptionService service;
  private final AdminAnalyticsService analyticsService;

  @GetMapping("/summary")
  @Operation(summary = "Subscription counts by lifecycle status")
  public ApiResponse<SubscriptionSummary> summary() {
    return ApiResponse.ok(analyticsService.subscriptionSummary());
  }

  @PostMapping
  public ApiResponse<SubscriptionDTOs.SubscriptionResponse> create(
      @Valid @RequestBody SubscriptionDTOs.SubscriptionCreateRequest request) {
    return ApiResponse.ok(service.subscribeBrandToPlan(request));
  }

  @GetMapping("/brand/{brandId}")
  public ApiResponse<List<SubscriptionDTOs.SubscriptionResponse>> list(@PathVariable UUID brandId) {
    return ApiResponse.ok(service.getSubscriptionsForBrand(brandId));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> cancel(@PathVariable UUID id) {
    service.cancelSubscription(id);
    return ApiResponse.empty();
  }
}
