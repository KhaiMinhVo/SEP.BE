package com.influencermatch.backend.campaign.history;

import static com.influencermatch.backend.campaign.history.HistoryDtos.*;

import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import com.influencermatch.backend.common.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(
    name = "Campaign History",
    description =
        "Luồng 10: owner-only snapshots, provenance, conservative reports and Duplicate Brief")
@SecurityRequirement(name = "BearerAuth")
@PreAuthorize(
    "hasRole('BRAND') and hasAuthority('VIEW_OWN_CAMPAIGN') and"
        + " hasAuthority('VIEW_OWN_HISTORY_AND_INSIGHTS')")
public class CampaignHistoryController {
  private final CampaignHistoryService service;

  @GetMapping("/campaign-history")
  @Operation(
      summary = "List owned Campaign history",
      description =
          "All lifecycle states by default; dates are inclusive dates of stored createdAt; size"
              + " 1-100; q is a literal case-insensitive name substring.")
  public ApiResponse<PageResponse<CampaignItem>> list(
      @RequestParam(required = false) CampaignStatus status,
      @RequestParam(required = false) Platform platform,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate createdFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate createdTo,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.list(status, platform, createdFrom, createdTo, q, page, size));
  }

  @GetMapping("/campaigns/{id}/history")
  @Operation(
      summary = "Read Campaign snapshots and completed-only report",
      description =
          "Read-only. Historical source gaps remain unavailable; private data requires both History"
              + " and Campaign permissions. Multiple-collaboration revenue is not summed without"
              + " attribution evidence.")
  public ApiResponse<Detail> detail(@PathVariable UUID id, Authentication auth) {
    return ApiResponse.ok(service.detail(id, auth));
  }

  @GetMapping("/campaigns/{id}/history/contexts")
  public ApiResponse<PageResponse<Context>> contexts(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.contexts(id, page, size));
  }

  @GetMapping("/campaigns/{id}/history/collaborations")
  public ApiResponse<PageResponse<CollaborationItem>> collaborations(
      @PathVariable UUID id,
      @RequestParam(required = false) CollaborationStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.collaborations(id, status, page, size));
  }

  @GetMapping("/campaigns/{id}/history/collaborations/{collaborationId}")
  public ApiResponse<CollaborationDetail> collaboration(
      @PathVariable UUID id, @PathVariable UUID collaborationId) {
    return ApiResponse.ok(service.collaboration(id, collaborationId));
  }

  @GetMapping("/campaigns/{id}/duplicate-brief/preview")
  @Operation(
      summary = "Preview Duplicate Brief without writes or quota",
      description =
          "Select an owned context, or explicitly labelled current-record fallback. Dates are reset"
              + " and excluded data is never returned as new campaign input.")
  public ApiResponse<Preview> preview(
      @PathVariable UUID id, @RequestParam(required = false) UUID contextId) {
    return ApiResponse.ok(service.preview(id, contextId));
  }

  @PostMapping("/campaigns/{id}/duplicate-brief")
  @PreAuthorize(
      "hasRole('BRAND') and hasAuthority('VIEW_OWN_CAMPAIGN') and"
          + " hasAuthority('VIEW_OWN_HISTORY_AND_INSIGHTS') and hasAuthority('CREATE_CAMPAIGN')")
  @Operation(
      summary = "Confirm a new DRAFT from reviewed brief",
      description =
          "Requires Idempotency-Key, source revision/kind/context matching the preview and editable"
              + " draft input. No cooperation data, old feedback or embedding is copied. Quota is"
              + " deducted once.")
  public ResponseEntity<ApiResponse<DuplicateResult>> duplicate(
      @PathVariable UUID id,
      @Valid @RequestBody Duplicate body,
      @RequestHeader("Idempotency-Key") String key,
      Authentication auth) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.duplicate(id, body, key, auth)));
  }
}
