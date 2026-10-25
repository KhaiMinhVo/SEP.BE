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
  private Double m2Score;
  private Double m1Score;
  private Double m4Score;
  private Double followerScore;
  private Double engagementScore;
  private Double semanticMatchScore;
  private Double nicheMatchScore;
  private Double finalScore;
}
