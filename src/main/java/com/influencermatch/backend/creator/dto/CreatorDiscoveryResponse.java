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
    BigDecimal engagementRate,
    String niche,
    String location,
    String contentSummary
) {}
