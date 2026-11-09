package com.influencermatch.backend.recommendation.learning;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LearningPolicyTest {
  final Instant now = Instant.parse("2026-11-09T12:00:00Z");
  final UUID brand = UUID.randomUUID(),
      other = UUID.randomUUID(),
      master = UUID.randomUUID(),
      channel = UUID.randomUUID();

  LearningPolicy.Evidence row(UUID b, UUID m, int rating, int fit, Boolean time, Double kpi) {
    return new LearningPolicy.Evidence(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        b,
        m,
        channel,
        now.minusSeconds(1),
        "AWARENESS",
        "tiktok",
        "skin",
        rating,
        fit,
        time,
        kpi);
  }

  List<LearningPolicy.Evidence> rows(int n) {
    var rows = new ArrayList<LearningPolicy.Evidence>();
    for (int i = 0; i < n; i++) rows.add(row(i % 2 == 0 ? brand : other, master, 5, 4, true, .5));
    return rows;
  }

  LearningPolicy.Snapshot snapshot(List<LearningPolicy.Evidence> rows) {
    return LearningPolicy.snapshot(now, rows);
  }

  @Test
  void noPriorOnlyScoreAndThresholdsBelongToEachSignal() {
    assertThat(snapshot(List.of()).m1(master, channel, "AWARENESS").score()).isNull();
    assertThat(snapshot(rows(2)).m1(master, channel, "AWARENESS").score()).isNull();
    var score = snapshot(rows(3)).m1(master, channel, "AWARENESS");
    assertThat(score.score()).isCloseTo((.5 * 80 + .3 * 80 + .1 * 50) / .9, within(1e-9));
    assertThat(
            score.review().effectiveWeight()
                + score.timeliness().effectiveWeight()
                + score.kpi().effectiveWeight())
        .isCloseTo(1, within(1e-9));
    assertThat(score.kpi().qualityFactor()).isEqualTo(.5);
    assertThat(score.support()).isCloseTo(.54, within(1e-9));
  }

  @Test
  void oneBrandCannotEstablishGeneralM1() {
    var rows =
        List.of(
            row(brand, master, 5, 5, true, 1.0),
            row(brand, master, 5, 5, true, 1.0),
            row(brand, master, 5, 5, true, 1.0));
    assertThat(snapshot(rows).m1(master, channel, "AWARENESS").review().unavailableReason())
        .isEqualTo("INSUFFICIENT_BRAND_DIVERSITY");
  }

  @Test
  void objectiveMismatchOnlyDisablesKpiAndExternalIsUnknownNotLate() {
    var rows =
        List.of(
            row(brand, master, 4, 5, null, .5),
            row(other, master, 4, 5, null, .5),
            row(brand, master, 4, 5, null, .5));
    var score = snapshot(rows).m1(master, channel, "TRAFFIC");
    assertThat(score.review().score()).isEqualTo(65);
    assertThat(score.score()).isEqualTo(65);
    assertThat(score.kpi().score()).isNull();
    assertThat(score.timeliness().score()).isNull();
    assertThat(score.review().effectiveWeight()).isEqualTo(1);
  }

  @Test
  void replayAndManyChannelsDoNotDuplicateReviewOrCollaboration() {
    var rows = rows(3);
    var repeated = new ArrayList<>(rows);
    repeated.addAll(rows);
    assertThat(snapshot(repeated).evidence()).hasSize(3);
    assertThat(snapshot(repeated).m1(master, UUID.randomUUID(), "AWARENESS").score())
        .isEqualTo(snapshot(rows).m1(master, channel, "AWARENESS").score());
  }

  @Test
  void historicalOwnerIsNotReplacedByCurrentOwnerAndUnclaimedHistoryIsNotInherited() {
    assertThat(snapshot(rows(3)).m1(UUID.randomUUID(), channel, "AWARENESS").score()).isNull();
    var data =
        List.of(
            row(brand, null, 4, 4, true, null),
            row(other, null, 4, 4, true, null),
            row(brand, null, 4, 4, true, null));
    assertThat(snapshot(data).m1(master, channel, "AWARENESS").score()).isNull();
    assertThat(snapshot(data).m1(null, channel, "AWARENESS").review().score()).isEqualTo(65);
    assertThat(snapshot(data).m1(null, channel, "AWARENESS").score()).isEqualTo(70.625);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void ratingUsesStatedPriorAndMonotonicScale(int rating) {
    assertThat(LearningPolicy.bayes(List.of(rating, rating, rating)))
        .isCloseTo(100 * (1 + 3 * (rating - 1) / 4.0) / 5, within(1e-9));
  }

  @Test
  void positiveAndNegativeReviewsMovePosteriorWithoutOneSidedFiltering() {
    double base = LearningPolicy.bayes(List.of(3, 3, 3));
    assertThat(LearningPolicy.bayes(List.of(3, 3, 3, 5))).isGreaterThan(base);
    assertThat(LearningPolicy.bayes(List.of(3, 3, 3, 1))).isLessThan(base);
    assertThatThrownBy(() -> LearningPolicy.bayes(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void timeWindowHasExactInclusiveBoundaryAndNoFutureEvidence() {
    var e = row(brand, master, 5, 5, true, null);
    var cutoff = LearningPolicy.cutoff(now);
    var at =
        new LearningPolicy.Evidence(
            e.id(),
            e.reviewId(),
            e.collaborationId(),
            e.brandId(),
            master,
            channel,
            cutoff,
            e.objective(),
            e.platform(),
            e.niche(),
            5,
            5,
            true,
            null);
    assertThat(snapshot(List.of(at)).evidence()).hasSize(1);
    assertThat(LearningPolicy.snapshot(now.minusSeconds(1), List.of(e)).evidence()).hasSize(1);
    assertThat(LearningPolicy.snapshot(now.minusSeconds(2), List.of(e)).evidence()).isEmpty();
    assertThat(LearningPolicy.snapshot(now.plusSeconds(1), List.of(at)).evidence()).isEmpty();
  }

  @Test
  void personalM4WinsAndUsesOnlyOwnBrand() {
    var data =
        List.of(
            row(brand, master, 3, 5, null, null),
            row(brand, master, 3, 5, null, null),
            row(brand, master, 3, 1, null, null),
            row(other, master, 3, 5, null, null));
    var c = snapshot(data).m4(brand, master, "instagram", "other");
    assertThat(c.scope()).isEqualTo("BRAND_MASTER");
    assertThat(c.count()).isEqualTo(3);
    assertThat(c.score()).isEqualTo(60);
    assertThat(c.support()).isEqualTo(.6);
  }

  @Test
  void platformCohortRequiresFiveReviewsAndThreeMasters() {
    var data = new ArrayList<LearningPolicy.Evidence>();
    var m2 = UUID.randomUUID();
    for (int i = 0; i < 5; i++) data.add(row(brand, i % 2 == 0 ? master : m2, 4, 5, null, null));
    assertThat(snapshot(data).m4(brand, null, "tiktok", "skin")).isNull();
    data.add(row(brand, UUID.randomUUID(), 4, 1, null, null));
    var c = snapshot(data).m4(brand, null, "TIKTOK", "Skin");
    assertThat(c.scope()).isEqualTo("PLATFORM_NICHE");
    assertThat(c.count()).isEqualTo(6);
    assertThat(c.score()).isEqualTo(75);
    assertThat(snapshot(data).m4(brand, null, "instagram", "skin")).isNull();
  }

  @Test
  void mixedPlatformHistoryCanOnlyContributeToPersonalCohort() {
    var data = new ArrayList<LearningPolicy.Evidence>();
    for (var e : rows(6))
      data.add(
          new LearningPolicy.Evidence(
              e.id(),
              e.reviewId(),
              e.collaborationId(),
              brand,
              master,
              channel,
              e.reviewedAt(),
              e.objective(),
              null,
              null,
              4,
              5,
              null,
              null));
    assertThat(snapshot(data).m4(brand, master, "tiktok", "skin").scope())
        .isEqualTo("BRAND_MASTER");
    assertThat(snapshot(data).m4(brand, UUID.randomUUID(), "tiktok", "skin")).isNull();
  }
}
