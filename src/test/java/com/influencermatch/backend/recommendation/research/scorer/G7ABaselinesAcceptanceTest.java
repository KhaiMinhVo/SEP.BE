package com.influencermatch.backend.recommendation.research.scorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse.CandidateEntry;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot.BrandPatternSpec;
import com.influencermatch.backend.recommendation.research.contracts.HistoricalCollaborationEvidence;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedCreatorContextExtension;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord.PostObservation;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricUnit;
import com.influencermatch.backend.recommendation.research.scorer.ResearchBaselineScorers.BaselineMethod;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class G7ABaselinesAcceptanceTest {

  private static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant T_AS_OF = Instant.parse("2026-11-25T00:00:00Z");
  private static final Instant T_FUTURE = Instant.parse("2026-11-26T00:00:00Z");

  @Test
  void fullyObservedEvidenceProducesEquivalentScoresAcrossAllFourBaselines() {
    UUID campId = UUID.randomUUID();
    UUID brandId = UUID.randomUUID();
    UUID otherBrand = UUID.randomUUID();
    UUID c1 = UUID.fromString("00000000-0000-0000-0000-000000000701");
    UUID c2 = UUID.fromString("00000000-0000-0000-0000-000000000702");

    List<HistoricalCollaborationEvidence> hist = new ArrayList<>();
    for (UUID cid : List.of(c1, c2)) {
      double r = cid.equals(c1) ? 1.0 : 0.6;
      for (int i = 0; i < 3; i++) {
        hist.add(collab(cid, null, (i < 2) ? brandId : otherBrand, campId, r, r, r, r));
      }
    }

    var snap = new ExtendedCampaignSnapshot(
        ExtendedCampaignSnapshot.SCHEMA_VERSION,
        new CampaignCreatorSnapshot(CampaignCreatorSnapshot.SCHEMA_VERSION, "full", campId, "AWARENESS",
            List.of("TIKTOK"), List.of("BEAUTY"), null, null, null, T_AS_OF, DataOrigin.REAL,
            List.of(obs(c1, "TIKTOK", 200.0, 0.05, false, false), obs(c2, "TIKTOK", 200.0, 0.05, false, false)), hist),
        brandId, List.of(new BrandPatternSpec("BEAUTY", "BEAUTY", "TIKTOK", 0.90)),
        Map.of(c1, ext(c1, "BEAUTY", 0.80), c2, ext(c2, "BEAUTY", 0.40)));
    var universe = universe(campId, List.of(c1, c2));

    var legacy = ResearchBaselineScorers.create(BaselineMethod.LEGACY_REFERENCE_V1, true).score(snap, universe);
    var strict = ResearchBaselineScorers.create(BaselineMethod.STRICT_GATING_BASELINE_V1, true).score(snap, universe);
    var neutral = ResearchBaselineScorers.create(BaselineMethod.NEUTRAL_IMPUTATION_BASELINE_V1, true).score(snap, universe);
    var afr = ResearchBaselineScorers.create(BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1, true).score(snap, universe);

    assertThat(legacy.mainRanked()).hasSize(2);
    assertThat(strict.mainRanked()).hasSize(2);
    assertThat(neutral.mainRanked()).hasSize(2);
    assertThat(afr.mainRanked()).hasSize(2);

    for (int i = 0; i < 2; i++) {
      double expected = legacy.mainRanked().get(i).matchScore();
      assertThat(strict.mainRanked().get(i).matchScore()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
      assertThat(neutral.mainRanked().get(i).matchScore()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
      assertThat(afr.mainRanked().get(i).matchScore()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
    }
  }

  @Test
  void missingFeaturesProduceDistinctBehaviorAcrossBaselinesAndPreserveZeroVsMissingAndIsolation() {
    UUID campId = UUID.randomUUID();
    UUID brandId = UUID.randomUUID();
    UUID cNoM1 = UUID.fromString("00000000-0000-0000-0000-000000000801"); // valid M2/M3/M4, no M1
    UUID cObsZero = UUID.fromString("00000000-0000-0000-0000-000000000802"); // observed zero views + ER=0
    UUID cMissingM2 = UUID.fromString("00000000-0000-0000-0000-000000000803"); // missing views & ER, valid M3
    UUID cInvalid = UUID.fromString("00000000-0000-0000-0000-000000000804"); // INVALID followers
    UUID cFb = UUID.fromString("00000000-0000-0000-0000-000000000805"); // NOT_APPLICABLE views (FACEBOOK)

    var snap = new ExtendedCampaignSnapshot(
        ExtendedCampaignSnapshot.SCHEMA_VERSION,
        new CampaignCreatorSnapshot(CampaignCreatorSnapshot.SCHEMA_VERSION, "miss", campId, "AWARENESS",
            List.of("TIKTOK", "FACEBOOK"), List.of("BEAUTY"), null, null, null, T_AS_OF, DataOrigin.REAL,
            List.of(
                obs(cNoM1, "TIKTOK", 500.0, 0.10, false, false),
                obs(cObsZero, "TIKTOK", 0.0, 0.0, false, false),
                obs(cMissingM2, "TIKTOK", null, null, false, false),
                obs(cInvalid, "TIKTOK", 250.0, 0.05, true, false),
                obs(cFb, "FACEBOOK", null, 0.02, false, true)),
            List.of()),
        brandId, List.of(new BrandPatternSpec("BEAUTY", "BEAUTY", "TIKTOK", 0.90)),
        Map.of(cNoM1, ext(cNoM1, "BEAUTY", 0.80), cObsZero, ext(cObsZero, "BEAUTY", 0.50),
            cMissingM2, ext(cMissingM2, "BEAUTY", 0.90), cFb, ext(cFb, "BEAUTY", 0.60)));
    var universe = universe(campId, List.of(cNoM1, cObsZero, cMissingM2, cInvalid, cFb));

    var legacy = ResearchBaselineScorers.create(BaselineMethod.LEGACY_REFERENCE_V1, false).score(snap, universe);
    var strict = ResearchBaselineScorers.create(BaselineMethod.STRICT_GATING_BASELINE_V1, false).score(snap, universe);
    var neutral = ResearchBaselineScorers.create(BaselineMethod.NEUTRAL_IMPUTATION_BASELINE_V1, false).score(snap, universe);
    var afr = ResearchBaselineScorers.create(BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1, false).score(snap, universe);

    // STRICT routes cNoM1 to PROVISIONAL (since M1 unavailable), excludes cMissingM2 & cInvalid & cFb
    assertThat(strict.mainRanked()).isEmpty();
    assertThat(strict.provisionalRanked()).extracting(r -> r.creatorId()).contains(cNoM1);

    // LEGACY excludes cMissingM2, cInvalid, cFb; keeps cNoM1 in MAIN (gate=false)
    assertThat(legacy.mainRanked()).extracting(r -> r.creatorId()).contains(cNoM1);
    assertThat(legacy.excluded()).extracting(r -> r.creatorId()).contains(cMissingM2, cInvalid, cFb);

    // NEUTRAL imputes missing M2/M1/M4 to 0.50, keeping observed zero at 0.0 (cMissingM2 > cObsZero!)
    assertThat(neutral.mainRanked()).extracting(r -> r.creatorId()).contains(cNoM1, cObsZero, cMissingM2, cFb);
    assertThat(neutral.excluded()).extracting(r -> r.creatorId()).containsExactly(cInvalid);

    // AFR omits missing M1/M2 instead of imputing 0.50 -> cNoM1 gets higher score than under NEUTRAL (0.50 drags it down)
    double afrNoM1 = afr.mainRanked().stream().filter(r -> r.creatorId().equals(cNoM1)).findFirst().orElseThrow().matchScore();
    double neutralNoM1 = neutral.mainRanked().stream().filter(r -> r.creatorId().equals(cNoM1)).findFirst().orElseThrow().matchScore();
    assertThat(afrNoM1).isNotEqualTo(neutralNoM1);
    assertThat(afrNoM1).isGreaterThan(neutralNoM1);

    // All matchScores finite, no NaN/Infinity
    assertThat(afr.mainRanked()).allMatch(r -> Double.isFinite(r.matchScore()));
    assertThat(neutral.mainRanked()).allMatch(r -> Double.isFinite(r.matchScore()));

    // Temporal leakage check
    var futureSnap = new ExtendedCampaignSnapshot(
        ExtendedCampaignSnapshot.SCHEMA_VERSION,
        new CampaignCreatorSnapshot(CampaignCreatorSnapshot.SCHEMA_VERSION, "f", campId, "AWARENESS",
            List.of("TIKTOK"), List.of(), null, null, null, T_AS_OF, DataOrigin.REAL,
            List.of(), List.of(collab(cNoM1, null, brandId, campId, 1.0, 1.0, 1.0, 1.0, T_FUTURE))),
        brandId, List.of(), Map.of());
    assertThatThrownBy(() -> ResearchBaselineScorers.create(BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1, false)
        .score(futureSnap, universe))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TEMPORAL_LEAKAGE");
  }

  private CandidateUniverse universe(UUID campId, List<UUID> ids) {
    return new CandidateUniverse(CandidateUniverse.SCHEMA_VERSION, campId, "u1", DataOrigin.REAL, "TEST",
        ids.stream().map(id -> new CandidateEntry(id, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of())).toList());
  }

  private ObservedEvidenceRecord obs(UUID cid, String plat, Double avgViews, Double er, boolean invalidFollowers, boolean isFb) {
    var f = invalidFollowers
        ? MetricObservation.invalid(MetricUnit.COUNT, "-10", 1, "T", T0, T1)
        : MetricObservation.observed(1_000.0, MetricUnit.COUNT, 1, 1, "T", T0, T1);
    var v = isFb
        ? MetricObservation.notApplicable(MetricUnit.COUNT, "FB", T0, T1)
        : (avgViews == null
            ? MetricObservation.missing(MetricUnit.COUNT, 3, "T", T0, T1)
            : MetricObservation.observed(avgViews, MetricUnit.COUNT, 2, 2, "T", T0, T1));
    var erObs = (er == null)
        ? MetricObservation.missing(MetricUnit.DECIMAL_RATIO, 2, "T", T0, T1)
        : MetricObservation.observed(er, MetricUnit.DECIMAL_RATIO, 2, 2, "T", T0, T1);
    List<PostObservation> posts = (avgViews == null || isFb) ? List.of() : List.of(
        new PostObservation("p1", T0.minusSeconds(120), v, MetricObservation.observed(Math.round(avgViews * (er != null ? er : 0.0)), MetricUnit.COUNT, 1, 1, "T", T0, T1), null, null),
        new PostObservation("p2", T0.minusSeconds(60), v, MetricObservation.observed(Math.round(avgViews * (er != null ? er : 0.0)), MetricUnit.COUNT, 1, 1, "T", T0, T1), null, null));
    return new ObservedEvidenceRecord(ObservedEvidenceRecord.SCHEMA_VERSION, cid, null, plat, DataOrigin.REAL, "s1", T0, T1,
        f, v, null, null, null, erObs, posts);
  }

  private ObservedCreatorContextExtension ext(UUID cid, String niche, double cosine) {
    return new ObservedCreatorContextExtension(
        ObservedCreatorContextExtension.SCHEMA_VERSION, cid, DataOrigin.REAL, niche, niche,
        MetricObservation.observed(cosine, MetricUnit.COSINE_SIMILARITY, 1, 1, "E", T0, T1), T0, T1);
  }

  private HistoricalCollaborationEvidence collab(
      UUID cid, UUID mid, UUID bid, UUID campId, double r, double t, double k, double rev) {
    return collab(cid, mid, bid, campId, r, t, k, rev, T1);
  }

  private HistoricalCollaborationEvidence collab(
      UUID cid, UUID mid, UUID bid, UUID campId, double r, double t, double k, double rev, Instant knownAt) {
    return new HistoricalCollaborationEvidence(
        HistoricalCollaborationEvidence.SCHEMA_VERSION, UUID.randomUUID(), cid, mid, bid, campId,
        DataOrigin.REAL, T0, knownAt, 1, "COMPLETED",
        MetricObservation.observed(r, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, knownAt),
        MetricObservation.observed(t, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, knownAt),
        MetricObservation.observed(k, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, knownAt),
        MetricObservation.observed(rev, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, knownAt));
  }
}
