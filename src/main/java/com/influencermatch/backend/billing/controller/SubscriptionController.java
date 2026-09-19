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

@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController extends BaseController {

  private final SubscriptionService subscriptionService;

  @PostMapping
  @PreAuthorize("hasRole('BRAND') or hasRole('ADMIN')")
  public ResponseEntity<ApiResponse<SubscriptionDTOs.SubscriptionResponse>> subscribe(
      @Valid @RequestBody SubscriptionDTOs.SubscriptionCreateRequest request) {
    SubscriptionDTOs.SubscriptionResponse response =
        subscriptionService.subscribeBrandToPlan(request);
    return created("Subscription created successfully", response);
  }

  @GetMapping("/brand/{brandId}")
  @PreAuthorize("hasRole('BRAND') or hasRole('ADMIN')")
  public ResponseEntity<ApiResponse<List<SubscriptionDTOs.SubscriptionResponse>>>
      getBrandSubscriptions(@PathVariable UUID brandId) {
    return ok(
        "Subscriptions retrieved successfully",
        subscriptionService.getSubscriptionsForBrand(brandId));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('BRAND') or hasRole('ADMIN')")
  public ResponseEntity<ApiResponse<Void>> cancelSubscription(@PathVariable UUID id) {
    subscriptionService.cancelSubscription(id);
    return ok("Subscription cancelled successfully");
  }
}
