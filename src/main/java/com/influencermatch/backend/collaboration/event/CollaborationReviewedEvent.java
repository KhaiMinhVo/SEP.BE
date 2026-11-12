package com.influencermatch.backend.collaboration.event;

import java.util.UUID;

public record CollaborationReviewedEvent(
    UUID collaborationId,
    UUID reviewId,
    UUID outcomeId,
    UUID campaignId,
    UUID brandProfileId,
    UUID creatorId,
    UUID masterId,
    String platform,
    short overallRating,
    short brandFitRating,
    Boolean wouldCollaborateAgain,
    String note) {}
