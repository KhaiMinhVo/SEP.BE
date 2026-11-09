package com.influencermatch.backend.brand;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.service.AiRecommendationService;
import java.util.*;
import org.junit.jupiter.api.Test;

class BrandM4ScoringTest {
  final HistoricalCampaignRepository histories = mock(HistoricalCampaignRepository.class);
  final ObjectMapper json = new ObjectMapper();
  final AiRecommendationService service =
      new AiRecommendationService(
          mock(com.influencermatch.backend.billing.service.SubscriptionService.class),
          mock(com.influencermatch.backend.relationship.service.CampaignOwnershipService.class),
          mock(CampaignContextRepository.class),
          mock(com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository.class),
          histories,
          mock(com.influencermatch.backend.brand.repository.BrandPatternRepository.class),
          mock(com.influencermatch.backend.relationship.repository.ShortlistItemRepository.class),
          mock(RecommendationRunRepository.class),
          mock(com.influencermatch.backend.creator.client.AiServiceClient.class),
          json);

  RecommendationCandidateProjection candidate() {
    var c = mock(RecommendationCandidateProjection.class);
    when(c.getCreatorId()).thenReturn(UUID.randomUUID());
    when(c.getPlatform()).thenReturn("TIKTOK");
    when(c.getNiche()).thenReturn("Skin");
    when(c.getFollowers()).thenReturn(25000L);
    return c;
  }

  final UUID brandId = UUID.randomUUID();

  com.influencermatch.backend.recommendation.learning.LearningPolicy.Snapshot learned(int n) {
    var rows =
        new ArrayList<
            com.influencermatch.backend.recommendation.learning.LearningPolicy.Evidence>();
    var now = java.time.Instant.now();
    for (int i = 0; i < n; i++)
      rows.add(
          new com.influencermatch.backend.recommendation.learning.LearningPolicy.Evidence(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              brandId,
              UUID.randomUUID(),
              UUID.randomUUID(),
              now.minusSeconds(1),
              "AWARENESS",
              "TIKTOK",
              "Skin",
              4,
              4,
              null,
              null));
    return com.influencermatch.backend.recommendation.learning.LearningPolicy.snapshot(now, rows);
  }

  @Test
  void unavailableHistoryRenormalizesAndReportsInsufficientEvidence() {
    var c = candidate();
    var score =
        service.calculateFourMemoryScores(
            c, Campaign.builder().build(), brandId, learned(4), json.createObjectNode());
    assertThat(score.getM1Score()).isNull();
    assertThat(score.getM4Score()).isNull();
    assertThat(score.getM4EvidenceCount()).isEqualTo(4);
    assertThat(score.getEffectiveWeights())
        .containsEntry("M2", 1.0)
        .containsEntry("M1", 0.0)
        .containsEntry("M4", 0.0);
    assertThat(score.getFinalScore()).isEqualTo(score.getM2Score());
  }

  @Test
  void unsupportedLegacyHistoryDoesNotInventM1AndBayesianWeightsSumToOne() {
    var c = candidate();
    var score =
        service.calculateFourMemoryScores(
            c, Campaign.builder().build(), brandId, learned(5), json.createObjectNode());
    assertThat(score.getM1Score()).isNull();
    assertThat(score.getM1UnavailableReason()).isEqualTo("INSUFFICIENT_COLLABORATIONS");
    assertThat(score.getScoringPolicyVersion()).isEqualTo("four-memory-v3-bayesian-history");
    assertThat(score.getM4Score()).isEqualTo(67.86);
    assertThat(score.getEffectiveWeights().get("M1")).isZero();
    assertThat(score.getEffectiveWeights().get("M2")).isCloseTo(5.0 / 7, within(0.000001));
    assertThat(score.getEffectiveWeights().get("M4")).isCloseTo(2.0 / 7, within(0.000001));
    assertThat(score.getEffectiveWeights().values().stream().mapToDouble(Double::doubleValue).sum())
        .isCloseTo(1, within(0.00001));
  }

  @Test
  void legacyUnverifiedHistoryDoesNotBecomeNeutralSuccess() {
    var c = candidate();
    var score =
        service.calculateFourMemoryScores(
            c, Campaign.builder().build(), brandId, learned(5), json.createObjectNode());
    assertThat(score.isM1Available()).isFalse();
    assertThat(score.isM4Available()).isTrue();
    assertThat(score.getEffectiveWeights().get("M4")).isCloseTo(2.0 / 7, within(0.000001));
  }

  @Test
  void campaignPreferencesWinAndBrandOnlySuppliesUnspecifiedSoftContext() {
    var brand =
        json.createObjectNode()
            .put("industry", "Beauty")
            .put("positioning", "Natural")
            .put("guidelines", "No medical claims");
    var c = Campaign.builder().niches(List.of("Food")).productService("Restaurant").build();
    String brief = AiRecommendationService.withBrandPreferences("Campaign Food", c, brand);
    assertThat(brief)
        .contains("Campaign Food", "No medical claims")
        .doesNotContain("Beauty", "Natural");
    c.setNiches(List.of());
    c.setProductService(null);
    assertThat(AiRecommendationService.withBrandPreferences("Campaign", c, brand))
        .contains("Beauty", "Natural");
  }
}
