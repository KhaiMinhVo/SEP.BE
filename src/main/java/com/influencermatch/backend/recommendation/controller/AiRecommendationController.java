package com.influencermatch.backend.recommendation.controller;

import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.recommendation.dto.*;
import com.influencermatch.backend.recommendation.engine.presentation.enums.DisplayMode;
import com.influencermatch.backend.recommendation.service.AiRecommendationService;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/campaigns/{campaignId}/ai-recommendations")
@RequiredArgsConstructor
@Tag(name = "AI Recommendation", description = "Four-Memory AI KOC Recommendation Engine")
public class AiRecommendationController {

  private final AiRecommendationService recommendationService;
  private final RecommendationRunRepository recommendationRunRepository;
  private final CampaignOwnershipService ownership;

  @PostMapping
  @PreAuthorize("hasAuthority('RUN_RECOMMENDATION')")
  @Operation(
      summary = "Score campaign shortlist using Four-Memory Framework",
      description =
         "Select creators from database discovery and save them to the campaign shortlist first. "
             + "Only SHORTLIST is accepted; DISCOVERY is rejected. Requires Idempotency-Key. "
             + "A DRAFT campaign is transitioned to READY_FOR_DISCOVERY; if embedding is still "
             + "processing, retry this request with the same key after checking GET "
             + "/campaigns/{id}.",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<RecommendationResponseDto>> recommendKocs(
      @PathVariable UUID campaignId,
      @RequestParam(defaultValue = "30") int limit,
      @RequestParam(required = false, defaultValue = "BEST_MATCH") DisplayMode displayMode,
      @RequestParam(defaultValue = "SHORTLIST")
          com.influencermatch.backend.campaign.enums.CandidateSource candidateSource,
      @RequestParam(required = false) List<UUID> creatorIds,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String revision) {
    RecommendationResponseDto result =
        recommendationService.recommendKocs(
            campaignId, limit, candidateSource, displayMode, creatorIds, idempotencyKey, revision);
    return ResponseEntity.ok(ApiResponse.ok("KOC recommendations generated successfully", result));
  }

  @GetMapping("/runs")
  @PreAuthorize("hasAuthority('VIEW_OWN_RECOMMENDATION')")
  @Operation(
      summary = "Get historical AI recommendation runs for campaign",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<List<RecommendationRunView>>> getRecommendationRuns(
      @PathVariable UUID campaignId) {
    return ResponseEntity.ok(ApiResponse.ok(recommendationService.runs(campaignId)));
  }

  @PostMapping("/feedback")
  @PreAuthorize("hasAuthority('RUN_RECOMMENDATION')")
  @Operation(
      summary = "Submit feedback on recommended candidate (e.g. REJECT with reasonCode)",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<RecommendationFeedbackResponse>> processFeedback(
      @PathVariable UUID campaignId, @Valid @RequestBody RecommendationFeedbackRequest request) {
    RecommendationFeedbackResponse response =
        recommendationService.processFeedback(campaignId, request);
    return ResponseEntity.ok(ApiResponse.ok("Feedback processed successfully", response));
  }
}
