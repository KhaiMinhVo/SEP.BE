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
          "Owned ACTIVE campaign; COLLABORATING, INTERESTED, or CONTACTED relationship from the same"
              + " campaign. Agreed fee is optional metadata (not a platform payment).")
  @ApiResponse(responseCode = "201", description = "Collaboration created")
  public ResponseEntity<com.influencermatch.backend.common.dto.ApiResponse<CollaborationResponse>>
      create(@PathVariable UUID id, @Valid @RequestBody CreateCollaborationRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(com.influencermatch.backend.common.dto.ApiResponse.ok(service.create(id, request)));
  }

  @GetMapping("/campaigns/{id}/collaborations")
  @PreAuthorize("hasAuthority('MANAGE_OWN_COLLABORATION')")
  @Operation(
      summary = "Get collaboration list for a campaign",
      description =
          "Owned campaign. Returns paginated collaborations with creator info and deliverables"
              + " progress.")
  @ApiResponse(responseCode = "200", description = "Collaboration list retrieved")
  public ResponseEntity<com.influencermatch.backend.common.dto.ApiResponse<PageResponse<CollaborationSummaryDto>>>
      getCampaignCollaborations(
          @PathVariable UUID id,
          @RequestParam(name = "status", required = false) CollaborationStatus status,
          @PageableDefault(size = 20) Pageable pageable) {
    return ResponseEntity.ok(
        com.influencermatch.backend.common.dto.ApiResponse.ok(
            service.getCampaignCollaborations(id, status, pageable)));
  }

  @GetMapping("/collaborations/{id}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Get collaboration detail",
      description =
          "Owned by campaign brand owner or assigned creator master. Returns full detail with"
              + " outcome and review.")
  @ApiResponse(responseCode = "200", description = "Collaboration detail retrieved")
  public ResponseEntity<com.influencermatch.backend.common.dto.ApiResponse<CollaborationDetailResponse>>
      getCollaborationDetail(@PathVariable UUID id) {
    return ResponseEntity.ok(
        com.influencermatch.backend.common.dto.ApiResponse.ok(
            service.getCollaborationDetail(id)));
  }

  @PostMapping("/collaborations/{id}/outcome")
  @PreAuthorize("hasAuthority('RECORD_OWN_OUTCOME')")
  @Operation(
      summary = "Record a collaboration outcome",
      description =
          "Owned ACTIVE campaign and IN_PROGRESS collaboration. At least one metric and an HTTP(S)"
              + " post link are required. Metrics are SELF_REPORTED. Completes the collaboration"
              + " and relationship.")
  @ApiResponse(responseCode = "201", description = "Outcome recorded")
  public ResponseEntity<com.influencermatch.backend.common.dto.ApiResponse<OutcomeResponse>>
      outcome(@PathVariable UUID id, @Valid @RequestBody RecordOutcomeRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            com.influencermatch.backend.common.dto.ApiResponse.ok(
                service.recordOutcome(id, request)));
  }

  @PostMapping("/collaborations/{id}/review")
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
