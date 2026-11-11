package com.influencermatch.backend.creator.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorIngestionRequest {
  private String platform;
  private String externalId;
  private String userName;
  private String displayName;
  private String avatarUrl;
  private String creatorType;
  private String location;
  private String profileUrl;

  // Metrics
  private Long followers;
  private BigDecimal avgViews;
  private BigDecimal avgLikes;
  private BigDecimal avgComments;
  private BigDecimal avgShares;
  private BigDecimal engagementRate;

  // Additional data
  private String niche;
  private String contentSummary;
  private String contact;
  private String category;

  private String freshnessStatus;
  private BigDecimal dataConfidence;
  
  private List<PostDto> recentPosts;
  private List<Float> embedding;

  // Identity & Provenance
  private Boolean isStableExternalId;
  private String crawlRunId;
  private java.time.LocalDateTime observedAt;
  private List<String> bioCrossLinks;
}
