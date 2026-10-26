package com.influencermatch.backend.collaboration.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.brand.repository.BrandPatternRepository;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.collaboration.event.CollaborationReviewedEvent;
import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import com.influencermatch.backend.collaboration.model.Collaboration;
import com.influencermatch.backend.collaboration.repository.CampaignOutcomeRepository;
import com.influencermatch.backend.collaboration.repository.CollaborationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollaborationCompletedEventListener {

  private final HistoricalCampaignRepository historicalCampaignRepository;
  private final BrandPatternRepository brandPatternRepository;
  private final CollaborationRepository collaborationRepository;
  private final CampaignOutcomeRepository campaignOutcomeRepository;
  private final BrandProfileRepository brandProfileRepository;
  private final ObjectMapper objectMapper;

  @EventListener
  @Transactional
  public void onCollaborationReviewed(CollaborationReviewedEvent event) {
    log.info(
        "Processing CollaborationReviewedEvent for collaborationId={}, creatorId={}",
        event.collaborationId(),
        event.creatorId());
    try {
      syncHistoricalCampaign(event);
      syncBrandPattern(event);
    } catch (Exception ex) {
      log.warn(
          "Failed to sync M1/M4 memory for collaborationId={}: {}",
          event.collaborationId(),
          ex.getMessage(),
          ex);
    }
  }

  private void syncHistoricalCampaign(CollaborationReviewedEvent event) {
    Collaboration collaboration =
        collaborationRepository.findById(event.collaborationId()).orElse(null);
    if (collaboration == null) {
      log.warn("Collaboration not found for M1 sync: {}", event.collaborationId());
      return;
    }

    CampaignOutcome outcome =
        campaignOutcomeRepository.findByCollaborationId(event.collaborationId()).orElse(null);

    double normalizedReliability = (event.overallRating() + event.brandFitRating()) / 10.0;

    ObjectNode evidenceValue = objectMapper.createObjectNode();
    evidenceValue.put("overallRating", event.overallRating());
    evidenceValue.put("brandFitRating", event.brandFitRating());
    if (event.wouldCollaborateAgain() != null) {
      evidenceValue.put("wouldCollaborateAgain", event.wouldCollaborateAgain());
    }
    evidenceValue.put("reliabilityScore", normalizedReliability);
    if (outcome != null) {
      if (outcome.getViews() != null) evidenceValue.put("views", outcome.getViews());
      if (outcome.getClicks() != null) evidenceValue.put("clicks", outcome.getClicks());
      if (outcome.getLikes() != null) evidenceValue.put("likes", outcome.getLikes());
      if (outcome.getComments() != null) evidenceValue.put("comments", outcome.getComments());
      if (outcome.getShares() != null) evidenceValue.put("shares", outcome.getShares());
    }
    if (collaboration.getPublishedContentUrl() != null) {
      evidenceValue.put("publishedContentUrl", collaboration.getPublishedContentUrl());
    }

    HistoricalCampaign historicalCampaign =
        HistoricalCampaign.builder()
            .creator(collaboration.getCreator())
            .master(
                collaboration.getMaster() != null
                    ? collaboration.getMaster()
                    : (collaboration.getCreator() != null
                        ? collaboration.getCreator().getMaster()
                        : null))
            .collaboration(collaboration)
            .outcome(outcome)
            .evidenceType("COLLABORATION_REVIEW")
            .evidenceValue(evidenceValue)
            .textValue(event.note())
            .quality("VERIFIED")
            .recordedAt(LocalDateTime.now())
            .build();

    historicalCampaignRepository.save(historicalCampaign);
    log.info(
        "Successfully saved M1 HistoricalCampaign for creatorId={}, collaborationId={}, quality=VERIFIED",
        event.creatorId(),
        event.collaborationId());
  }

  private void syncBrandPattern(CollaborationReviewedEvent event) {
    if (event.brandProfileId() == null) {
      return;
    }

    brandProfileRepository
        .findById(event.brandProfileId())
        .ifPresent(
            brandProfile -> {
              BrandPattern pattern =
                  brandPatternRepository
                      .findByBrandProfileIdAndPatternType(
                          brandProfile.getId(), "COLLABORATION_SUCCESS")
                      .orElseGet(
                          () -> {
                            ObjectNode initialValue = objectMapper.createObjectNode();
                            initialValue.put("successCount", 0);
                            return BrandPattern.builder()
                                .brandProfile(brandProfile)
                                .platform(event.platform())
                                .patternType("COLLABORATION_SUCCESS")
                                .patternValue(initialValue)
                                .confidence(BigDecimal.valueOf(0.50))
                                .evidenceCount(0)
                                .learnedAt(LocalDateTime.now())
                                .build();
                          });

              int updatedCount = pattern.getEvidenceCount() + 1;
              pattern.setEvidenceCount(updatedCount);

              double newConfidence = Math.min(1.0, 0.50 + 0.05 * updatedCount);
              pattern.setConfidence(
                  BigDecimal.valueOf(newConfidence).setScale(4, RoundingMode.HALF_UP));

              double score = (event.overallRating() + event.brandFitRating()) / 2.0;
              pattern.setAverageSuccessfulScore(
                  BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP));

              ObjectNode patternValue =
                  (pattern.getPatternValue() instanceof ObjectNode)
                      ? (ObjectNode) pattern.getPatternValue()
                      : objectMapper.createObjectNode();

              patternValue.put("successCount", updatedCount);
              patternValue.put("lastOverallRating", event.overallRating());
              patternValue.put("lastBrandFitRating", event.brandFitRating());
              if (event.wouldCollaborateAgain() != null) {
                patternValue.put("lastWouldCollaborateAgain", event.wouldCollaborateAgain());
              }
              pattern.setPatternValue(patternValue);
              pattern.setLearnedAt(LocalDateTime.now());

              brandPatternRepository.save(pattern);
              log.info(
                  "Successfully updated M4 BrandPattern for brandProfileId={}, evidenceCount={}, confidence={}",
                  brandProfile.getId(),
                  updatedCount,
                  pattern.getConfidence());
            });
  }
}
