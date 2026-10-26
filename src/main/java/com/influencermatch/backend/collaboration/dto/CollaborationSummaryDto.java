package com.influencermatch.backend.collaboration.dto;

import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CollaborationSummaryDto(
    UUID id,
    UUID campaignId,
    UUID relationshipId,
    UUID creatorId,
    String creatorName,
    String platform,
    String avatarUrl,
    BigDecimal agreedFee,
    String currency,
    CollaborationStatus status,
    String publishedContentUrl,
    String note,
    LocalDateTime startAt,
    LocalDateTime completedAt,
    LocalDateTime updatedAt,
    boolean hasOutcome,
    boolean hasReview) {}
