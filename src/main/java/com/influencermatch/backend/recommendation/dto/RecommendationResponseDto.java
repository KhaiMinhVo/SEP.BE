package com.influencermatch.backend.recommendation.dto;

import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationResponseDto {
  private UUID recommendationRunId;
  private UUID campaignId;
  private int contextVersion;
  private int totalCandidates;
  private List<CreatorRecommendationItemDto> recommendations;
}
