package com.influencermatch.backend.admin.controller;

import com.influencermatch.backend.admin.dto.AdminAnalyticsDtos.CampaignSummary;
import com.influencermatch.backend.admin.dto.AdminAnalyticsDtos.PlanRevenue;
import com.influencermatch.backend.admin.dto.AdminAnalyticsDtos.UserGrowth;
import com.influencermatch.backend.admin.service.AdminAnalyticsService;
import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
@RequiredArgsConstructor
@Validated
public class AdminAnalyticsController {
  private final AdminAnalyticsService service;

  @GetMapping("/admin/campaigns/summary")
  @PreAuthorize("hasAuthority('VIEW_DATA_MONITORING')")
  @Operation(summary = "Campaign counts by lifecycle status")
  public ApiResponse<CampaignSummary> campaignSummary() {
    return ApiResponse.ok(service.campaignSummary());
  }

  @GetMapping("/admin/analytics/user-growth")
  @PreAuthorize("hasAuthority('VIEW_DATA_MONITORING')")
  @Operation(
      summary = "Monthly new-user counts",
      description =
          "Returns one bucket per month, including empty months and the current partial month.")
  public ApiResponse<UserGrowth> userGrowth(
      @RequestParam(defaultValue = "6") @Min(1) @Max(60) int months) {
    return ApiResponse.ok(service.userGrowth(months));
  }

  @GetMapping("/admin/analytics/revenue-by-plan")
  @PreAuthorize("hasAuthority('MANAGE_SUBSCRIPTION')")
  @Operation(
      summary = "Settled revenue by subscription plan",
      description =
          "Sums only PAID payment transactions; REFUNDED, PENDING, and FAILED transactions are "
              + "excluded. Results are separated by payment currency.")
  public ApiResponse<List<PlanRevenue>> revenueByPlan() {
    return ApiResponse.ok(service.revenueByPlan());
  }
}
