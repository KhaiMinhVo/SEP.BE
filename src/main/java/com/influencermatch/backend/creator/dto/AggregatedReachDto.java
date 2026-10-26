package com.influencermatch.backend.creator.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
public class AggregatedReachDto {

  @Schema(description = "Master profile ID (M1)")
  private UUID masterId;

  @Schema(description = "Canonical / Display name of Master KOC")
  private String canonicalName;

  @Schema(description = "Total number of verified social channels linked")
  private int channelCount;

  @Schema(description = "Total aggregated followers across all linked platforms")
  private Long totalFollowers;

  @Schema(description = "Total average views across all linked platforms")
  private BigDecimal totalAvgViews;

  @Schema(description = "Weighted average engagement rate across all channels")
  private BigDecimal averageEngagementRate;

  @Schema(description = "List of connected social media platforms")
  private List<String> platforms;

  @Schema(description = "Detailed breakdown per connected channel")
  private List<ChannelReachDto> platformBreakdown;

  @Schema(description = "Timestamp when reach was calculated")
  private LocalDateTime calculatedAt;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ChannelReachDto {
    @Schema(description = "Creator channel record ID")
    private UUID channelId;

    @Schema(description = "Social platform (TIKTOK, YOUTUBE, FACEBOOK)")
    private String platform;

    @Schema(description = "Handle / Username")
    private String userName;

    @Schema(description = "Display name")
    private String displayName;

    @Schema(description = "Avatar URL")
    private String avatarUrl;

    @Schema(description = "Profile link")
    private String profileUrl;

    @Schema(description = "Followers count")
    private Long followers;

    @Schema(description = "Average views count")
    private BigDecimal avgViews;

    @Schema(description = "Engagement rate")
    private BigDecimal engagementRate;

    @Schema(description = "OAuth token status (ACTIVE, EXPIRED, DISCONNECTED)")
    private String tokenStatus;

    @Schema(description = "Verification method used during OAuth claim")
    private String verificationMethod;

    @Schema(description = "Timestamp when channel was authorized")
    private LocalDateTime authorizedAt;
  }
}
