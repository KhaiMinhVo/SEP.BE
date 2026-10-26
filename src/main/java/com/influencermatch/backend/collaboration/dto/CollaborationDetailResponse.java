package com.influencermatch.backend.collaboration.dto;

import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CollaborationDetailResponse(
    UUID id,
    UUID campaignId,
    String campaignName,
    String brandName,
    UUID relationshipId,
    UUID creatorId,
    String creatorName,
    String platform,
    String avatarUrl,
    String profileUrl,
    BigDecimal agreedFee,
    String currency,
    CollaborationStatus status,
    String publishedContentUrl,
    String note,
    LocalDateTime startAt,
    LocalDateTime completedAt,
    LocalDateTime updatedAt,
    OutcomeResponse outcome,
    CreatorReviewResponse review) {}
