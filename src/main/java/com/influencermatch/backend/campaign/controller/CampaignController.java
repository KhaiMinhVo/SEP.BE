package com.influencermatch.backend.campaign.controller;

import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.campaign.service.*;
import com.influencermatch.backend.common.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequiredArgsConstructor
@Tag(name = "Campaigns", description = "Brand-owned campaign setup and lifecycle")
@SecurityRequirement(name = "BearerAuth")
public class CampaignController {
  private final CampaignService service;

  @PostMapping("/brands/{brandId}/campaigns")
  @Operation(summary = "Create a draft campaign")
  public ResponseEntity<ApiResponse<CampaignResponse>> create(
      @PathVariable UUID brandId,
      @Valid @RequestBody CampaignRequest request,
      Authentication auth) {
    CampaignResponse result = service.create(brandId, request, auth);
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/campaigns/{id}")
            .buildAndExpand(result.id())
            .toUri();
    return ResponseEntity.created(location).body(ApiResponse.ok(result));
  }

  @Operation(summary = "List campaigns owned by a brand profile")
  @GetMapping("/brands/{brandId}/campaigns")
  public ApiResponse<PageResponse<CampaignResponse>> list(
      @PathVariable UUID brandId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication auth) {
    return ApiResponse.ok(
        service.list(
            brandId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)), auth));
  }

  @Operation(summary = "Get campaign details")
  @GetMapping("/campaigns/{id}")
  public ApiResponse<CampaignResponse> get(@PathVariable UUID id, Authentication auth) {
    return ApiResponse.ok(service.get(id, auth));
  }

  @Operation(summary = "Update a draft campaign")
  @PutMapping("/campaigns/{id}")
  public ApiResponse<CampaignResponse> update(
      @PathVariable UUID id, @Valid @RequestBody CampaignRequest request, Authentication auth) {
    return ApiResponse.ok(service.update(id, request, auth));
  }

  @Operation(
      summary = "Change campaign status",
      description =
          "Allowed: DRAFT -> READY_FOR_DISCOVERY -> ACTIVE -> COMPLETED; DRAFT -> ARCHIVED")
  @PatchMapping("/campaigns/{id}/status")
  public ApiResponse<CampaignResponse> status(
      @PathVariable UUID id,
      @Valid @RequestBody CampaignStatusRequest request,
      Authentication auth) {
    return ApiResponse.ok(service.changeStatus(id, request, auth));
  }

  @Operation(summary = "Archive a draft campaign")
  @PatchMapping("/campaigns/{id}/archive")
  public ApiResponse<CampaignResponse> archive(@PathVariable UUID id, Authentication auth) {
    return ApiResponse.ok(service.archive(id, auth));
  }
}
