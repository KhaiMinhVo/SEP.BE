package com.influencermatch.backend.collaboration.dto;

import com.influencermatch.backend.collaboration.enums.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CollaborationResponse(
    UUID id,
    UUID campaignId,
    UUID relationshipId,
    UUID creatorId,
    BigDecimal agreedFee,
    String currency,
    CollaborationStatus status,
    CollaborationPaymentStatus paymentStatus,
    String publishedContentUrl,
    String note,
    LocalDateTime startAt,
    LocalDateTime completedAt,
    LocalDateTime updatedAt) {}
