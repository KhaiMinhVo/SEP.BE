package com.influencermatch.backend.relationship.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortlistCrawlEvent {
  private String eventType; // CRAWL_REQUESTED, CRAWL_REQUEST_FAILED, CRAWL_COMPLETED, CONNECTED
  private UUID metricId;
  private LocalDateTime collectedAt;
  private UUID campaignId;
  private UUID creatorId;
  private String creatorName;
  private String platform;
  private String userName;
  private String avatarUrl;
  private Long followers;
  private BigDecimal avgViews;
  private BigDecimal avgLikes;
  private BigDecimal avgComments;
  private BigDecimal avgShares;
  private BigDecimal engagementRate;
  private String message;
  private LocalDateTime timestamp;
}
