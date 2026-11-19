package com.influencermatch.backend.recommendation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoreBreakdownDto {
  private Double m1Score;
  private Double m2Score;
  private Double m3Score;
  private Double m4Score;
  private Double followerScore;
  private Double engagementScore;
  private Double semanticMatchScore;
  private Double nicheMatchScore;
  private Double finalScore;

  // SP-01: M1 R/T/K & Coverage Metadata
  @Builder.Default
  private String m1Availability = "UNAVAILABLE";
  private Double rScore;
  private Double tScore;
  private Double kScore;
  private Integer eligibleEvaluatedCollaborations;
  private Integer distinctBrandCount;
  private Double maxBrandShare;

  // SP-01: M4 Evidence Hierarchy Metadata
  private Double m4LearnedScore;
  private Double m4ExplicitScore;
  private Double m4EffectiveScore;
  @Builder.Default
  private String m4Source = "EXPLICIT_FALLBACK";

  // SP-01: Confidence Score & Tier
  private Double confidenceScore;
  private String confidenceTier;
}
