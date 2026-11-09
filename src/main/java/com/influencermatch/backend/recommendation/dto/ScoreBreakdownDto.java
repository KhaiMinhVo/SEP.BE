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
  @com.fasterxml.jackson.annotation.JsonIgnore private Double rankingScore;

  public record M1Details(
      com.influencermatch.backend.recommendation.learning.LearningPolicy.Signal review,
      com.influencermatch.backend.recommendation.learning.LearningPolicy.Signal timeliness,
      com.influencermatch.backend.recommendation.learning.LearningPolicy.Signal kpi,
      String scope,
      double evidenceSupport) {}

  private M1Details m1Details;
  private String m4Cohort;
  private int m4MasterCount;
  private double evidenceCoverage;
  private String evidenceAsOf;
  private String evidenceCutoff;
  private Double m2Score;

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  private Double m1Score;

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  private Double m4Score;

  private Double followerScore;
  private Double engagementScore;
  private Double semanticMatchScore;
  private Double nicheMatchScore;
  private Double finalScore;
  private boolean m1Available;
  private boolean m4Available;
  private String m1UnavailableReason;
  private String m4UnavailableReason;
  private int m1EvidenceCount;
  private int m4EvidenceCount;
  private Double m4Confidence;
  private java.util.List<java.util.UUID> m4ReviewIds;
  private java.util.Map<String, Double> originalWeights;
  private java.util.Map<String, Double> effectiveWeights;
  private String scoringPolicyVersion;
}
