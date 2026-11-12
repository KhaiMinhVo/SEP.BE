package com.influencermatch.backend.collaboration.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CreatorReviewResponse(
    UUID id,
    UUID collaborationId,
    UUID outcomeId,
    UUID reviewedByUserId,
    short overallRating,
    short brandFitRating,
    Boolean wouldCollaborateAgain,
    String note,
    LocalDateTime reviewedAt) {}
