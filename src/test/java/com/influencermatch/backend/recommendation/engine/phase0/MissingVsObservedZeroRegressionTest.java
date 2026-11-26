package com.influencermatch.backend.recommendation.engine.phase0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.memory.PublicPerformanceScorer;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MissingVsObservedZeroRegressionTest {

  private final EvidenceValidator validator = new EvidenceValidator();
  private final Phase0Orchestrator orchestrator = new Phase0Orchestrator();
  private final PublicPerformanceScorer scorer = new PublicPerformanceScorer();

  @Test
  void allMissingInteractionsDoNotBecomeObservedZeroEr() {
    var cand = candidate("TIKTOK", 10_000L, null, null, null);
    List<Post> postsMissingInteractions = List.of(
        Post.builder().platformPostId("v1").views(1_000L).postedAt("2026-11-01T00:00:00Z").build(),
        Post.builder().platformPostId("v2").views(2_000L).postedAt("2026-11-02T00:00:00Z").build()
    );

    var evidence = validator.evaluate(cand, postsMissingInteractions);
    assertThat(evidence.rawEr()).isNaN();
    assertThat(evidence.scoringReadiness()).isEqualTo(ScoringReadiness.NOT_SCORABLE);
  }

  @Test
  void observedZeroInteractionsRemainZeroErWhenMeasured() {
    var cand = candidate("TIKTOK", 10_000L, null, null, null);
    List<Post> postsObservedZeroLikes = List.of(
        Post.builder().platformPostId("v1").views(1_000L).likes(0L).postedAt("2026-11-01T00:00:00Z").build(),
        Post.builder().platformPostId("v2").views(2_000L).likes(0L).postedAt("2026-11-02T00:00:00Z").build()
    );

    var evidence = validator.evaluate(cand, postsObservedZeroLikes);
    assertThat(evidence.rawEr()).isZero();
    assertThat(evidence.scoringReadiness()).isEqualTo(ScoringReadiness.SCORE_READY);
  }

  @Test
  void partialMissingViewsDoNotDiluteAverageViewsDenominator() {
    var cand = candidate("TIKTOK", 1_000L, null, null, null);
    List<Post> mixedPosts = List.of(
        Post.builder().platformPostId("v1").views(100L).likes(10L).postedAt("2026-11-01T00:00:00Z").build(),
        Post.builder().platformPostId("p2").views(null).likes(20L).postedAt("2026-11-02T00:00:00Z").build(),
        Post.builder().platformPostId("v3").views(200L).likes(20L).postedAt("2026-11-03T00:00:00Z").build()
    );

    var evidence = validator.evaluate(cand, mixedPosts);
    // Average views over 2 valid posts = (100 + 200) / 2 = 150 -> VR = 150 / 1000 = 0.15
    assertThat(evidence.rawVr()).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-12));
  }

  @Test
  void missingVsObservedZeroViewsAndInapplicableFacebookKpi() {
    // Missing views on TikTok -> NOT_SCORABLE (MISSING_VIEW_METRICS)
    var tiktokCand = candidate("TIKTOK", 10_000L, null, null, null);
    var missingViewsEv = validator.evaluate(tiktokCand, List.of(
        Post.builder().platformPostId("p1").views(null).likes(50L).postedAt("2026-11-01T00:00:00Z").build()
    ));
    assertThat(missingViewsEv.rawVr()).isNaN();
    assertThat(missingViewsEv.scoringReadiness()).isEqualTo(ScoringReadiness.NOT_SCORABLE);

    // Facebook photo posts -> VR is NOT_APPLICABLE (NaN, NOT 0.0)
    var fbCand = candidate("FACEBOOK", 10_000L, null, null, null);
    var fbEv = validator.evaluate(fbCand, List.of(
        Post.builder().platformPostId("f1").views(null).likes(100L).postedAt("2026-11-01T00:00:00Z").build()
    ));
    assertThat(fbEv.rawVr()).isNaN();
    assertThat(fbEv.rawEr()).isEqualTo(0.01);
    assertThat(fbEv.scoringReadiness()).isEqualTo(ScoringReadiness.NOT_SCORABLE);
  }

  @Test
  void negativeMetricsRemainInvalidDataNotMissing() {
    var cand = candidate("TIKTOK", 10_000L, BigDecimal.valueOf(-1), null, null);
    var context = orchestrator.execute(
        List.of(cand),
        Campaign.builder().platforms(List.of("TIKTOK")).build(),
        Set.of(),
        Map.of()
    ).getFirst();
    assertThat(context.scoringReadiness()).isEqualTo(ScoringReadiness.INVALID_DATA);
  }

  @Test
  void standaloneM2RejectsMissingAvgViewsOrMissingErInsteadOfInventingZero() {
    var missingViewsCand = candidate("TIKTOK", 1_000L, null, BigDecimal.valueOf(10), BigDecimal.valueOf(0.05));
    assertThatThrownBy(() -> scorer.score(null, missingViewsCand))
        .isInstanceOf(IllegalArgumentException.class);

    var missingErCand = candidate("TIKTOK", 1_000L, BigDecimal.valueOf(100), null, null);
    assertThatThrownBy(() -> scorer.score(null, missingErCand))
        .isInstanceOf(IllegalArgumentException.class);

    var observedZeroCand = candidate("TIKTOK", 1_000L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    var result = scorer.score(null, observedZeroCand);
    assertThat(result.erNorm()).isZero();
    assertThat(result.vrNorm()).isZero();
  }

  private RecommendationCandidateProjection candidate(
      String platform, Long followers, BigDecimal avgViews, BigDecimal avgLikes, BigDecimal er) {
    var cand = mock(RecommendationCandidateProjection.class);
    when(cand.getCreatorId()).thenReturn(UUID.randomUUID());
    when(cand.getPlatform()).thenReturn(platform);
    when(cand.getAccountStatus()).thenReturn("ACTIVE");
    when(cand.getFollowers()).thenReturn(followers);
    when(cand.getAvgViews()).thenReturn(avgViews);
    when(cand.getAvgLikes()).thenReturn(avgLikes);
    when(cand.getEngagementRate()).thenReturn(er);
    return cand;
  }
}
