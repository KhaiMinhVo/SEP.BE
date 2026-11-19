package com.influencermatch.backend.collaboration.controller;

import com.influencermatch.backend.collaboration.dto.*;
import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import com.influencermatch.backend.collaboration.service.CollaborationService;
import com.influencermatch.backend.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(
    name = "Collaborations",
    description = "Brand-owned collaborations, self-reported outcomes and creator reviews")
@SecurityRequirement(name = "BearerAuth")
@ApiResponses({
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
  @ApiResponse(
      responseCode = "401",
      description = "Authentication required",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
  @ApiResponse(
      responseCode = "403",
      description = "Permission or ownership denied",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
  @ApiResponse(
      responseCode = "404",
      description = "Resource not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
  @ApiResponse(
      responseCode = "409",
      description = "Duplicate or workflow conflict",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
})
public class CollaborationController {
  private final CollaborationService service;

  @PostMapping("/campaigns/{id}/collaborations")
  @PreAuthorize("hasAuthority('MANAGE_OWN_COLLABORATION')")
  @Operation(
      summary = "Confirm a creator collaboration",
      description =
          "Compatibility entrypoint for Deal confirmation. Requires dealId, expectedRevision,"
              + " agreement source/evidence and Idempotency-Key. Creates PLANNED; no direct"
              + " CONTACTED bypass.")
  @ApiResponse(responseCode = "201", description = "Collaboration created")
  public ResponseEntity<com.influencermatch.backend.common.dto.ApiResponse<CollaborationResponse>>
      create(
          @PathVariable UUID id,
          @Valid @RequestBody CreateCollaborationRequest request,
          @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    if (request.dealId() == null || request.expectedRevision() == null)
      throw new com.influencermatch.backend.exception.ValidationException(
          "Confirm a Deal with its proposal revision and agreement evidence");
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            com.influencermatch.backend.common.dto.ApiResponse.ok(
                service.create(id, request, key)));
  }

  public ResponseEntity<com.influencermatch.backend.common.dto.ApiResponse<CollaborationResponse>>
      create(UUID id, CreateCollaborationRequest request) {
    return create(id, request, null);
  }

  @GetMapping("/campaigns/{id}/collaborations")
  @PreAuthorize("hasAuthority('MANAGE_OWN_COLLABORATION')")
  @Operation(
      summary = "Get collaboration list for a campaign",
      description =
          "Owned campaign. Returns paginated collaborations with creator info and deliverables"
              + " progress.")
  @ApiResponse(responseCode = "200", description = "Collaboration list retrieved")
  public ResponseEntity<
          com.influencermatch.backend.common.dto.ApiResponse<PageResponse<CollaborationSummaryDto>>>
      getCampaignCollaborations(
          @PathVariable UUID id,
          @RequestParam(name = "status", required = false) CollaborationStatus status,
          @PageableDefault(size = 20) Pageable pageable) {
    return ResponseEntity.ok(
        com.influencermatch.backend.common.dto.ApiResponse.ok(
            service.getCampaignCollaborations(id, status, pageable)));
  }

  @GetMapping("/collaborations/{id}")
  @PreAuthorize("(hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')) or (hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION'))")
  @Operation(
      summary = "Get collaboration detail",
      description =
          "Owned by campaign Brand or assigned Creator. Internal review and Brand evidence are visible to Brand only.")
  @ApiResponse(responseCode = "200", description = "Collaboration detail retrieved")
  public ResponseEntity<
          com.influencermatch.backend.common.dto.ApiResponse<CollaborationDetailResponse>>
      getCollaborationDetail(@PathVariable UUID id) {
    return ResponseEntity.ok(
        com.influencermatch.backend.common.dto.ApiResponse.ok(service.getCollaborationDetail(id)));
  }

  @PostMapping("/collaborations/{id}/evidence")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION')")
  @Operation(
      summary = "Submit deliverables and evidence for a collaboration",
      description =
          "Assigned Creator may attach evidence during active execution. This endpoint does not set official publication or submission.")
  @ApiResponse(responseCode = "201", description = "Evidence asset submitted")
  public ResponseEntity<com.influencermatch.backend.common.dto.ApiResponse<EvidenceAssetResponse>>
      submitEvidence(@PathVariable UUID id, @Valid @RequestBody SubmitEvidenceRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            com.influencermatch.backend.common.dto.ApiResponse.ok(
                service.submitEvidence(id, request)));
  }

  @GetMapping("/collaborations/{id}/evidence")
  @PreAuthorize("(hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')) or (hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION'))")
  @Operation(
      summary = "Get list of evidence assets for a collaboration",
      description =
          "Allowed for campaign brand owner or assigned creator master. Returns all submitted"
              + " evidence assets.")
  @ApiResponse(responseCode = "200", description = "Evidence assets retrieved")
  public ResponseEntity<
          com.influencermatch.backend.common.dto.ApiResponse<java.util.List<EvidenceAssetResponse>>>
      getEvidenceAssets(@PathVariable UUID id) {
    return ResponseEntity.ok(
        com.influencermatch.backend.common.dto.ApiResponse.ok(service.getEvidenceAssets(id)));
  }

  // Outcome execution routes are handled by CollaborationExecutionController.
  @PreAuthorize("hasAuthority('RECORD_OWN_OUTCOME')")
  @Operation(
      summary = "Record a collaboration outcome",
      description =
          "Owned ACTIVE campaign and IN_PROGRESS collaboration. At least one metric and an HTTP(S)"
              + " post link are required. Historical Java compatibility only; HTTP execution uses"
              + " revision-aware outcomes and explicit acceptance. Recording never completes work.")
  @ApiResponse(responseCode = "201", description = "Outcome recorded")
  public ResponseEntity<com.influencermatch.backend.common.dto.ApiResponse<OutcomeResponse>>
      outcome(@PathVariable UUID id, @Valid @RequestBody RecordOutcomeRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            com.influencermatch.backend.common.dto.ApiResponse.ok(
                service.recordOutcome(id, request)));
  }

  // Final review route is handled by CollaborationExecutionController.
  @PreAuthorize("hasAuthority('RECORD_OWN_CREATOR_REVIEW')")
  @Operation(
      summary = "Review a completed collaboration",
      description =
          "Owned completed collaboration with an outcome; campaign ACTIVE or COMPLETED. Integer"
              + " ratings 1–5; one review per collaboration.")
  @ApiResponse(responseCode = "201", description = "Creator review created")
  public ResponseEntity<com.influencermatch.backend.common.dto.ApiResponse<CreatorReviewResponse>>
      review(@PathVariable UUID id, @Valid @RequestBody CreateCreatorReviewRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(com.influencermatch.backend.common.dto.ApiResponse.ok(service.review(id, request)));
  }
}
