package com.influencermatch.backend.recommendation.controller;

import com.influencermatch.backend.campaign.model.RecommendationRun;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.recommendation.dto.*;
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
      summary = "Run AI KOC Recommendation using Four-Memory Framework",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<RecommendationResponseDto>> recommendKocs(
      @PathVariable UUID campaignId,
      @RequestParam(defaultValue = "30") int limit) {
    RecommendationResponseDto result = recommendationService.recommendKocs(campaignId, limit);
    return ResponseEntity.ok(ApiResponse.ok("KOC recommendations generated successfully", result));
  }

  @GetMapping("/runs")
  @PreAuthorize("hasAuthority('VIEW_OWN_RECOMMENDATION')")
  @Operation(
      summary = "Get historical AI recommendation runs for campaign",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<List<RecommendationRun>>> getRecommendationRuns(
      @PathVariable UUID campaignId) {
    ownership.requireOwned(campaignId);
    List<RecommendationRun> runs =
        recommendationRunRepository.findByCampaignIdOrderByCreatedAtDesc(campaignId);
    return ResponseEntity.ok(ApiResponse.ok(runs));
  }

  @PostMapping("/feedback")
  @PreAuthorize("hasAuthority('RUN_RECOMMENDATION')")
  @Operation(
      summary = "Submit feedback on recommended candidate (e.g. REJECT with reasonCode)",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<RecommendationFeedbackResponse>> processFeedback(
      @PathVariable UUID campaignId,
      @Valid @RequestBody RecommendationFeedbackRequest request) {
    RecommendationFeedbackResponse response =
        recommendationService.processFeedback(campaignId, request);
    return ResponseEntity.ok(ApiResponse.ok("Feedback processed successfully", response));
  }
}
