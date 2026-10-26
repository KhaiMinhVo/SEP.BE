package com.influencermatch.backend.collaboration.dto;

import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CreatorCollaborationDto(
    UUID id,
    UUID campaignId,
    String campaignName,
    String brandName,
    UUID creatorId,
    String platform,
    String channelName,
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
