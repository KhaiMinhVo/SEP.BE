package com.influencermatch.backend.recommendation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request body for submitting feedback on AI recommendation candidate")
public class RecommendationFeedbackRequest {

  @NotNull(message = "Creator ID is required")
  @Schema(description = "UUID of the KOC/Creator being reviewed", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
  private UUID creatorId;

  @NotBlank(message = "Action is required (e.g. REJECT, ACCEPT)")
  @Schema(description = "Decision action", example = "REJECT", allowableValues = {"REJECT", "ACCEPT"})
  private String action;

  @Schema(description = "Standard reason code for rejection or feedback", example = "PRICE_TOO_HIGH")
  private String reasonCode;

  @Schema(description = "Optional custom note from brand user", example = "KOC báo giá 30 triệu, vượt mức chi trả của đợt này")
  private String reasonNote;
}
