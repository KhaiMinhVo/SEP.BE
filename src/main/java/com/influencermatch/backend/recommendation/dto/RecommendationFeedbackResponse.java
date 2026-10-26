package com.influencermatch.backend.recommendation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response returned after processing AI recommendation feedback")
public class RecommendationFeedbackResponse {

  @Schema(description = "Campaign ID")
  private UUID campaignId;

  @Schema(description = "Creator ID that received feedback")
  private UUID creatorId;

  @Schema(description = "Action processed (REJECT, ACCEPT)")
  private String action;

  @Schema(description = "Reason code recorded")
  private String reasonCode;

  @Schema(description = "Context version before evolution")
  private int previousContextVersion;

  @Schema(description = "New context version after evolution")
  private int newContextVersion;

  @Schema(description = "Total number of creators excluded from future recommendations for this campaign")
  private int totalExcludedCount;

  @Schema(description = "Human-readable status message")
  private String message;
}
