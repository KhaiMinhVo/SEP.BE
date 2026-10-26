package com.influencermatch.backend.recommendation.dto;

import java.util.Collections;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplainRequestDto {
  @Builder.Default
  private String creatorName = "";
  private long followers;
  @Builder.Default
  private double engagementRate = 0.0;
  private Double engagementRateViews;
  @Builder.Default
  private String platform = "TIKTOK";
  @Builder.Default
  private String niche = "general";
  @Builder.Default
  private String category = "Lifestyle";
  @Builder.Default
  private String creatorType = "KOC";
  @Builder.Default
  private double avgViews = 0.0;
  @Builder.Default
  private double avgLikes = 0.0;
  @Builder.Default
  private double similarityScore = 0.0;
  @Builder.Default
  private String campaignBrief = "";
  @Builder.Default
  private String contentSummary = "";
  @Builder.Default
  private List<String> topTags = Collections.emptyList();
}
