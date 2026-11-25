package com.influencermatch.backend.recommendation.engine.phase0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PostRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.creator.service.CreatorIngestionService;
import com.influencermatch.backend.recommendation.engine.memory.PublicPerformanceScorer;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ErUnitContractRegressionTest {

  private final EvidenceValidator validator = new EvidenceValidator();
  private final Phase0Orchestrator orchestrator = new Phase0Orchestrator();
  private final PublicPerformanceScorer scorer = new PublicPerformanceScorer();

  @Test
  void canonicalDecimalRatioIsPreservedThroughIngestionPhase0AndM2WithoutDoubleDivision() {
    CreatorRepository creatorRepo = mock(CreatorRepository.class);
    PublicCreatorMetricRepository metricRepo = mock(PublicCreatorMetricRepository.class);
    PostRepository postRepo = mock(PostRepository.class);
    EntityManager em = mock(EntityManager.class);

    Creator savedCreator = Creator.builder().id(UUID.randomUUID()).platform("TIKTOK").externalId("er-1").build();
    when(creatorRepo.findLockedByPlatformAndExternalId(any(), any())).thenReturn(Optional.of(savedCreator));
    when(creatorRepo.save(any())).thenReturn(savedCreator);
    when(metricRepo.findByCreatorId(savedCreator.getId())).thenReturn(Optional.empty());
    var metricCaptor = org.mockito.ArgumentCaptor.forClass(PublicCreatorMetric.class);
    when(metricRepo.save(metricCaptor.capture())).thenAnswer(inv -> {
      PublicCreatorMetric m = inv.getArgument(0);
      m.setId(UUID.randomUUID());
      return m;
    });

    // Python JavaMapper already converted 5.0% -> 0.05 before calling Java ingestion
    var ingestionService = new CreatorIngestionService(creatorRepo, metricRepo, postRepo, em);
    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("er-1")
        .userName("er_creator")
        .creatorType("KOC")
        .followers(10_000L)
        .avgViews(BigDecimal.valueOf(20_000))
        .avgLikes(BigDecimal.valueOf(500))
        .engagementRate(new BigDecimal("0.0500"))
        .build();

    var response = ingestionService.ingestCreatorData(request);
    assertThat(response.getStatus()).isEqualTo("SUCCESS");
    assertThat(metricCaptor.getValue().getEngagementRate()).isEqualByComparingTo("0.0500");

    // Verify Phase0 + M2 use 0.05 directly (K_ER_VIEWS_TIKTOK = 0.05 -> erNormalized = 0.50)
    var candidate = candidate(savedCreator.getId(), new BigDecimal("0.0500"), BigDecimal.valueOf(20_000), BigDecimal.valueOf(500));
    var context = orchestrator.execute(
        List.of(candidate),
        Campaign.builder().platforms(List.of("TIKTOK")).build(),
        Set.of(),
        Map.of()
    ).getFirst();

    assertThat(context.rawEr()).isEqualTo(0.05);
    assertThat(context.erNormalized()).isCloseTo(0.50, org.assertj.core.data.Offset.offset(1e-12));
    assertThat(scorer.score(context, candidate).erNorm())
        .isCloseTo(0.50, org.assertj.core.data.Offset.offset(1e-12));
  }

  @Test
  void zeroAndNullAndInvalidErFollowCanonicalContract() {
    var zeroCand = candidate(UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.valueOf(20_000), null);
    var zeroEvidence = validator.evaluate(zeroCand, List.of());
    assertThat(zeroEvidence.rawEr()).isZero();
    assertThat(zeroEvidence.scoringReadiness()).isEqualTo(ScoringReadiness.PROVISIONAL);

    var missingCand = candidate(UUID.randomUUID(), null, null, null);
    var missingEvidence = validator.evaluate(missingCand, List.of());
    assertThat(missingEvidence.rawEr()).isNaN();
    assertThat(missingEvidence.scoringReadiness()).isEqualTo(ScoringReadiness.NOT_SCORABLE);

    var negativeCand = candidate(UUID.randomUUID(), new BigDecimal("-0.05"), BigDecimal.valueOf(20_000), null);
    assertThatThrownBy(() -> validator.evaluate(negativeCand, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NEGATIVE_METRIC");

    var outlierCand = candidate(UUID.randomUUID(), new BigDecimal("15.0"), BigDecimal.valueOf(20_000), null);
    assertThatThrownBy(() -> validator.evaluate(outlierCand, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EXTREME_OUTLIER_ER");
  }

  private RecommendationCandidateProjection candidate(
      UUID creatorId, BigDecimal er, BigDecimal avgViews, BigDecimal avgLikes) {
    var cand = mock(RecommendationCandidateProjection.class);
    when(cand.getCreatorId()).thenReturn(creatorId);
    when(cand.getPlatform()).thenReturn("TIKTOK");
    when(cand.getAccountStatus()).thenReturn("ACTIVE");
    when(cand.getFollowers()).thenReturn(10_000L);
    when(cand.getAvgViews()).thenReturn(avgViews);
    when(cand.getAvgLikes()).thenReturn(avgLikes);
    when(cand.getEngagementRate()).thenReturn(er);
    return cand;
  }
}
