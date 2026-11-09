package com.influencermatch.backend.brand;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.service.BrandLearnedPatterns;
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

  BrandLearnedPatterns.Snapshot learned(int n) {
    var evidence = new ArrayList<BrandLearnedPatterns.Evidence>();
    for (int i = 0; i < n; i++)
      evidence.add(new BrandLearnedPatterns.Evidence(UUID.randomUUID(), "TIKTOK", "Skin", 4));
    return BrandLearnedPatterns.aggregate(evidence);
  }

  @Test
  void unavailableHistoryRenormalizesAndReportsInsufficientEvidence() {
    var c = candidate();
    var score =
        service.calculateFourMemoryScores(
            c, Campaign.builder().build(), UUID.randomUUID(), learned(4));
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
  void historyDoesNotInventM1BeforePolicyExistsAndAvailableWeightsSumToOne() {
    var c = candidate();
    when(histories.findByCreatorId(c.getCreatorId()))
        .thenReturn(
            List.of(
                HistoricalCampaign.builder()
                    .quality("VERIFIED")
                    .outcome(
                        com.influencermatch.backend.collaboration.model.CampaignOutcome.builder()
                            .roi(java.math.BigDecimal.valueOf(2))
                            .build())
                    .build()));
    var score =
        service.calculateFourMemoryScores(
            c, Campaign.builder().build(), UUID.randomUUID(), learned(5));
    assertThat(score.getM1Score()).isNull();
    assertThat(score.getM1UnavailableReason()).isEqualTo("M1_POLICY_NOT_IMPLEMENTED");
    assertThat(score.getScoringPolicyVersion()).isEqualTo("four-memory-v2-m1-unavailable");
    assertThat(score.getM4Score()).isEqualTo(75);
    assertThat(score.getEffectiveWeights().get("M1")).isZero();
    assertThat(score.getEffectiveWeights().get("M2")).isCloseTo(5.0 / 7, within(0.000001));
    assertThat(score.getEffectiveWeights().get("M4")).isCloseTo(2.0 / 7, within(0.000001));
    assertThat(score.getEffectiveWeights().values().stream().mapToDouble(Double::doubleValue).sum())
        .isCloseTo(1, within(0.00001));
  }

  @Test
  void legacyUnverifiedHistoryDoesNotBecomeNeutralSuccess() {
    var c = candidate();
    when(histories.findByCreatorId(c.getCreatorId()))
        .thenReturn(List.of(HistoricalCampaign.builder().quality("LEGACY").build()));
    var score =
        service.calculateFourMemoryScores(
            c, Campaign.builder().build(), UUID.randomUUID(), learned(5));
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
