package com.influencermatch.backend.collaboration.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.collaboration.event.CollaborationReviewedEvent;
import com.influencermatch.backend.collaboration.repository.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Compatibility for historical reviews. Ratings always come from persisted rows, not the event. */
@Component
@RequiredArgsConstructor
public class CollaborationCompletedEventListener {
  private final HistoricalCampaignRepository history;
  private final BrandPatternRepository legacyPatterns;
  private final CollaborationRepository collaborations;
  private final CampaignOutcomeRepository outcomes;
  private final BrandProfileRepository brands;
  private final ObjectMapper mapper;
  @org.springframework.beans.factory.annotation.Autowired private CreatorReviewRepository reviews;

  @EventListener
  @Transactional
  public void onCollaborationReviewed(CollaborationReviewedEvent event) {
    var collaboration = collaborations.findLockedById(event.collaborationId()).orElseThrow();
    var review = reviews.findById(event.reviewId()).orElseThrow();
    var outcome = outcomes.findByCollaborationId(collaboration.getId()).orElseThrow();
    if (!review.getCollaboration().getId().equals(collaboration.getId())
        || !review.getOutcome().getId().equals(outcome.getId()))
      throw new IllegalStateException("Review source mismatch");
    if (history.findBySourceReviewIdAndSourceRevision(review.getId(), 1L).isPresent()) return;
    var proof =
        mapper
            .createObjectNode()
            .put("reviewerAuthenticated", true)
            .put("brandAccepted", false)
            .put("metricSource", "LEGACY_UNVERIFIED")
            .put("overallRating", review.getOverallRating())
            .put("brandFitRating", review.getBrandFitRating())
            .put("wouldCollaborateAgain", review.getWouldCollaborateAgain() != null && review.getWouldCollaborateAgain())
            .put("legacyWorkflow", true);
    if (outcome.getViews() != null) proof.put("views", outcome.getViews());
    if (outcome.getLikes() != null) proof.put("likes", outcome.getLikes());
    if (collaboration.getPublishedContentUrl() != null)
      proof.put("publishedContentUrl", collaboration.getPublishedContentUrl());
    UUID rootId = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime businessTime = review.getReviewedAt() != null ? review.getReviewedAt() : now;
    history.save(
        HistoricalCampaign.builder()
            .id(rootId)
            .evidenceRootId(rootId)
            .revisionNumber(1L)
            .schemaVersion("1.0")
            .creator(collaboration.getCreator())
            .master(collaboration.getMaster())
            .collaboration(collaboration)
            .outcome(outcome)
            .evidenceType("COLLABORATION_REVIEW")
            .evidenceValue(proof)
            .textValue(review.getNote())
            .quality("SELF_REPORTED")
            .sourceReviewId(review.getId())
            .sourceRevision(1L)
            .provenance("LEGACY_UNVERIFIED")
            .status("ACTIVE")
            .businessOccurredAt(businessTime)
            .recordedAt(now)
            .build());
    // Learned M4 is rebuilt from authoritative reviews at recommendation time; never increment
    // legacy counters.
  }
}
