package com.influencermatch.backend.relationship.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.service.ShortlistCompareService;
import com.influencermatch.backend.relationship.service.ShortlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/campaigns/{campaignId}/shortlist")
@RequiredArgsConstructor
@Tag(name = "Shortlist Management", description = "Campaign Shortlist Cart and Contact Readiness Management")
public class ShortlistController {

  private final ShortlistService shortlistService;
  private final ShortlistCompareService compareService;

  @PostMapping("/compare")
  @PreAuthorize("hasAuthority('MANAGE_OWN_SHORTLIST')")
  @Operation(
      summary = "Compare 2–5 shortlisted Creators without ranking",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ApiResponse<CompareDtos.Response> compare(
      @PathVariable UUID campaignId,
      @Valid @RequestBody CompareDtos.Request request) {
    return ApiResponse.ok(compareService.compare(campaignId, request));
  }

  @Operation(
      summary = "Add a creator to campaign shortlist (1-Click Cart Add)",
      security = @SecurityRequirement(name = "BearerAuth"))
  @PostMapping(value = {"", "/items"})
  @PreAuthorize("hasAuthority('MANAGE_OWN_SHORTLIST')")
  public ResponseEntity<ApiResponse<ShortlistItemDto>> addCreatorToShortlist(
      @PathVariable UUID campaignId,
      @Valid @RequestBody AddShortlistItemRequest request) {
    ShortlistItemDto dto = shortlistService.addCreatorToShortlist(campaignId, request);
    return ResponseEntity.ok(ApiResponse.ok(dto.getMessage(), dto));
  }

  @Operation(
      summary = "Get shortlist for a campaign with optional filtering",
      security = @SecurityRequirement(name = "BearerAuth"))
  @GetMapping
  @PreAuthorize("hasAuthority('MANAGE_OWN_SHORTLIST')")
  public ResponseEntity<ApiResponse<Page<ShortlistItemDto>>> getShortlist(
      @PathVariable UUID campaignId,
      @RequestParam(required = false) ShortlistStatus status,
      @RequestParam(required = false) ShortlistPriority priority,
      @RequestParam(required = false) String platform,
      @PageableDefault(size = 20) Pageable pageable) {
    Page<ShortlistItemDto> result =
        shortlistService.getShortlistByCampaign(campaignId, status, priority, platform, pageable);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @Operation(
      summary = "Update shortlist item priority, note, or status",
      security = @SecurityRequirement(name = "BearerAuth"))
  @PatchMapping(value = {"/{shortlistItemId}", "/items/{shortlistItemId}"})
  @PreAuthorize("hasAuthority('MANAGE_OWN_SHORTLIST')")
  public ResponseEntity<ApiResponse<ShortlistItemDto>> updateShortlistItem(
      @PathVariable UUID campaignId,
      @PathVariable UUID shortlistItemId,
      @RequestBody UpdateShortlistItemRequest request) {
    ShortlistItemDto updated =
        shortlistService.updateShortlistItem(campaignId, shortlistItemId, request);
    return ResponseEntity.ok(ApiResponse.ok("Shortlist item updated", updated));
  }

  @Operation(
      summary = "Batch update shortlist item status (e.g. mark READY_TO_CONTACT)",
      security = @SecurityRequirement(name = "BearerAuth"))
  @PatchMapping("/batch-status")
  @PreAuthorize("hasAuthority('MANAGE_OWN_SHORTLIST')")
  public ResponseEntity<ApiResponse<List<ShortlistItemDto>>> batchUpdateStatus(
      @PathVariable UUID campaignId,
      @Valid @RequestBody BatchUpdateStatusRequest request) {
    List<ShortlistItemDto> updated = shortlistService.batchUpdateStatus(campaignId, request);
    return ResponseEntity.ok(ApiResponse.ok("Batch status updated", updated));
  }

  @Operation(
      summary = "Remove a creator from shortlist (Soft delete)",
      security = @SecurityRequirement(name = "BearerAuth"))
  @DeleteMapping(value = {"/{shortlistItemId}", "/items/{shortlistItemId}"})
  @PreAuthorize("hasAuthority('MANAGE_OWN_SHORTLIST')")
  public ResponseEntity<ApiResponse<Void>> removeCreatorFromShortlist(
      @PathVariable UUID campaignId,
      @PathVariable UUID shortlistItemId) {
    shortlistService.removeCreatorFromShortlist(campaignId, shortlistItemId);
    return ResponseEntity.ok(ApiResponse.empty());
  }

  @Operation(
      summary = "FLOW SL-04: Contact Selection & Readiness Preview",
      security = @SecurityRequirement(name = "BearerAuth"))
  @PostMapping("/contact-readiness")
  @PreAuthorize("hasAuthority('MANAGE_OWN_SHORTLIST')")
  public ResponseEntity<ApiResponse<ContactReadinessResponse>> checkContactReadiness(
      @PathVariable UUID campaignId,
      @RequestBody(required = false) ContactReadinessRequest request) {
    ContactReadinessResponse response = shortlistService.checkContactReadiness(campaignId, request);
    return ResponseEntity.ok(ApiResponse.ok("Contact readiness preview generated", response));
  }

  @Operation(
      summary = "FLOW SL-05 / CRM Bridge: Shortlist Batch Outreach to CRM",
      security = @SecurityRequirement(name = "BearerAuth"))
  @PostMapping("/outreach")
  @PreAuthorize("hasAuthority('MANAGE_OWN_SHORTLIST')")
  public ResponseEntity<ApiResponse<ShortlistOutreachResponse>> outreach(
      @PathVariable UUID campaignId,
      @Valid @RequestBody ShortlistOutreachRequest request) {
    ShortlistOutreachResponse response = shortlistService.outreachShortlist(campaignId, request);
    return ResponseEntity.ok(ApiResponse.ok("Shortlist outreach processed", response));
  }
}
