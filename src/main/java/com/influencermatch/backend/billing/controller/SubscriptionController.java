package com.influencermatch.backend.billing.controller;

import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.dto.SubscriptionDTOs;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.common.BaseController;
import com.influencermatch.backend.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Brand subscription and quota management")
@SecurityRequirement(name = "BearerAuth")
public class SubscriptionController extends BaseController {

  private final SubscriptionService subscriptionService;

  @Operation(summary = "Subscribe brand to a plan")
  @PostMapping
  @PreAuthorize("hasAuthority('MANAGE_OWN_SUBSCRIPTION')")
  public ResponseEntity<ApiResponse<SubscriptionDTOs.SubscriptionResponse>> subscribe(
      @Valid @RequestBody SubscriptionDTOs.SubscriptionCreateRequest request) {
    SubscriptionDTOs.SubscriptionResponse response =
        subscriptionService.subscribeBrandToPlan(request);
    return created("Subscription created successfully", response);
  }

  @Operation(summary = "Get subscriptions for brand profile")
  @GetMapping("/brand/{brandId}")
  @PreAuthorize("hasAuthority('MANAGE_OWN_SUBSCRIPTION')")
  public ResponseEntity<ApiResponse<List<SubscriptionDTOs.SubscriptionResponse>>>
      getBrandSubscriptions(@PathVariable UUID brandId) {
    return ok(
        "Subscriptions retrieved successfully",
        subscriptionService.getSubscriptionsForBrand(brandId));
  }

  @Operation(summary = "Cancel active subscription")
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('MANAGE_OWN_SUBSCRIPTION')")
  public ResponseEntity<ApiResponse<Void>> cancelSubscription(@PathVariable UUID id) {
    subscriptionService.cancelSubscription(id);
    return ok("Subscription cancelled successfully");
  }
}
