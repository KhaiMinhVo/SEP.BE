package com.influencermatch.backend.recommendation.engine.phase0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.FourMemoryTopsisEngine;
import com.influencermatch.backend.recommendation.engine.memory.PublicPerformanceScorer;
import com.influencermatch.backend.recommendation.engine.phase0.enums.CampaignEligibility;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VrDenominatorRegressionTest {
  private final EvidenceValidator validator = new EvidenceValidator();
  private final Phase0Orchestrator orchestrator = new Phase0Orchestrator();

  enum ViewSource { POSTS, AGGREGATE, PHOTO_AGGREGATE }

  static Stream<Arguments> unavailableDenominators() {
    return Stream.of(ViewSource.values())
        .flatMap(source -> Stream.of(
            Arguments.of(source, 0L), Arguments.of(source, (Long) null)));
  }

  @ParameterizedTest
  @MethodSource("unavailableDenominators")
  void missingOrZeroFollowersNeverBecomeObservedZeroVr(ViewSource source, Long followers) {
    var candidate = candidate(followers);
    var evidence = validator.evaluate(candidate, posts(source));

    assertThat(evidence.rawVr()).isNaN();
    assertThat(evidence.scoringReadiness()).isEqualTo(ScoringReadiness.NOT_SCORABLE);
    assertThat(evidence.dataQualityReasons())
        .anyMatch(reason -> reason.startsWith("VR_UNAVAILABLE_MISSING_FOLLOWER_DENOMINATOR"));
    if (source == ViewSource.POSTS) {
      assertThat(evidence.rawEr()).isEqualTo(0.1);
      assertThat(evidence.rawStab()).isPositive().isFinite();
    } else if (source == ViewSource.AGGREGATE) {
      assertThat(evidence.rawEr()).isEqualTo(0.1);
    }
  }

  @ParameterizedTest
  @MethodSource("unavailableDenominators")
  void phase0KeepsEligibilityButBypassesNormalizationAndRejectsScoring(
      ViewSource source, Long followers) {
    var candidate = candidate(followers);
    var campaign = Campaign.builder().platforms(List.of("TIKTOK")).build();
    var context = orchestrator.execute(List.of(candidate), campaign, Set.of(),
        Map.of(candidate.getCreatorId(), posts(source))).getFirst();

    assertThat(context.campaignEligibility()).isEqualTo(CampaignEligibility.ELIGIBLE);
    assertThat(context.scoringReadiness()).isEqualTo(ScoringReadiness.NOT_SCORABLE);
    assertThat(context.rawVr()).isNaN();
    assertThat(context.vrNormalized()).isNaN();
    assertThat(context.erNormalized()).isNaN();
    assertThat(context.dataQualityReasons())
        .anyMatch(reason -> reason.startsWith("VR_UNAVAILABLE_MISSING_FOLLOWER_DENOMINATOR"));
    // An accidental engine or M2 scorer call must fail validation, rather than create a NaN score DTO.
    assertThatThrownBy(() -> new PublicPerformanceScorer().score(context, candidate))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FourMemoryTopsisEngine().evaluateSingle(
        candidate, context, campaign, List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("unavailableDenominators")
  void negativeFollowersRemainInvalidData(ViewSource source, Long ignored) {
    var candidate = candidate(-1L);
    assertThatThrownBy(() -> validator.evaluate(candidate, posts(source)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NEGATIVE_METRIC");
    var context = orchestrator.execute(List.of(candidate),
        Campaign.builder().platforms(List.of("TIKTOK")).build(), Set.of(),
        Map.of(candidate.getCreatorId(), posts(source))).getFirst();
    assertThat(context.campaignEligibility()).isEqualTo(CampaignEligibility.ELIGIBLE);
    assertThat(context.scoringReadiness()).isEqualTo(ScoringReadiness.INVALID_DATA);
  }

  @Test
  void positiveDenominatorPreservesPostAggregateAndPhotoFallbackRatios() {
    for (var source : ViewSource.values()) {
      var candidate = candidate(1_000L);
      var evidence = validator.evaluate(candidate, posts(source));
      assertThat(evidence.rawVr()).isEqualTo(0.15);
      var context = orchestrator.execute(List.of(candidate),
          Campaign.builder().platforms(List.of("TIKTOK")).build(), Set.of(),
          Map.of(candidate.getCreatorId(), posts(source))).getFirst();
      assertThat(context.scoringReadiness()).isEqualTo(source == ViewSource.POSTS
          ? ScoringReadiness.SCORE_READY : ScoringReadiness.PROVISIONAL);
      assertThat(context.vrNormalized()).isCloseTo(0.15 / 0.65,
          org.assertj.core.data.Offset.offset(1e-12));
    }
  }

  @Test
  void measuredZeroViewsWithPositiveFollowersRemainObservedZeroVr() {
    var candidate = candidate(1_000L);
    var evidence = validator.evaluate(candidate,
        List.of(post("zero-1", 0L, 0L), post("zero-2", 0L, 0L)));
    assertThat(evidence.rawVr()).isZero();
    assertThat(evidence.rawEr()).isNaN();
    assertThat(evidence.rawStab()).isNaN();
    assertThat(evidence.scoringReadiness()).isEqualTo(ScoringReadiness.NOT_SCORABLE);
  }

  @Test
  void standaloneM2CannotInventZeroVrForMissingZeroOrNegativeFollowers() {
    var scorer = new PublicPerformanceScorer();
    for (Long followers : new Long[] {null, 0L, -1L}) {
      assertThatThrownBy(() -> scorer.score(null, candidate(followers)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(scorer.score(null, candidate(1_000L)).vrNorm())
        .isCloseTo(0.15 / 0.65, org.assertj.core.data.Offset.offset(1e-12));
  }

  private RecommendationCandidateProjection candidate(Long followers) {
    var candidate = mock(RecommendationCandidateProjection.class);
    when(candidate.getCreatorId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000003"));
    when(candidate.getPlatform()).thenReturn("TIKTOK");
    when(candidate.getAccountStatus()).thenReturn("ACTIVE");
    when(candidate.getFollowers()).thenReturn(followers);
    when(candidate.getAvgViews()).thenReturn(BigDecimal.valueOf(150));
    when(candidate.getAvgLikes()).thenReturn(BigDecimal.valueOf(15));
    when(candidate.getEngagementRate()).thenReturn(BigDecimal.valueOf(0.1));
    return candidate;
  }

  private List<Post> posts(ViewSource source) {
    return switch (source) {
      case POSTS -> List.of(post("one", 100L, 10L), post("two", 200L, 20L));
      case AGGREGATE -> List.of();
      case PHOTO_AGGREGATE -> List.of(post("photo", null, 10L));
    };
  }

  private Post post(String id, Long views, Long likes) {
    return Post.builder().platformPostId(id).views(views).likes(likes)
        .postedAt("2026-11-01T00:00:00Z").build();
  }
}
