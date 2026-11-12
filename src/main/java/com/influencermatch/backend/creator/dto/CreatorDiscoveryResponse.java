package com.influencermatch.backend.creator.dto;

import lombok.Builder;
import java.math.BigDecimal;
import java.util.UUID;

@Builder
public record CreatorDiscoveryResponse(
    UUID creatorId,
    String platform,
    String userName,
    String profileUrl,
    Long followers,
    Long followerChange7d,
    BigDecimal followerGrowthRate,
    BigDecimal engagementRate,
    String niche,
    String location,
    String contentSummary,
    String displayName,
    String avatarUrl,
    String creatorType,
    BigDecimal estimatedFeeMin,
    BigDecimal estimatedFeeMax,
    BigDecimal actualFee,
    Boolean isClaimed,
    Boolean isSimilarFallback,
    java.util.List<PostDto> recentPosts
) {}
