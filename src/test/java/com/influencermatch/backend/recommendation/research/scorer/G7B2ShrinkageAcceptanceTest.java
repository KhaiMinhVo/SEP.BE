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
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DatasetSplit;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.dgp.SyntheticDgpGenerator;
import com.influencermatch.backend.recommendation.research.scorer.EvidenceConditionedShrinkageScorer.PriorMode;
import com.influencermatch.backend.recommendation.research.scorer.ResearchBaselineScorers.BaselineMethod;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class G7B2ShrinkageAcceptanceTest {

  private static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant T_AS_OF = Instant.parse("2026-11-25T00:00:00Z");
  private static final Instant T_FUTURE = Instant.parse("2026-11-26T00:00:00Z");

  private final EvidenceConditionedShrinkageScorer shrinkage = new EvidenceConditionedShrinkageScorer();

  @Test
  void tests1To5_m1ClusterDesignEffectiveSupportMissingRtkColdStartAndObservedZero() {
    UUID campId = UUID.randomUUID();
    UUID brand1 = UUID.randomUUID();
    UUID c3Same = UUID.fromString("00000000-0000-0000-0000-000000000901");
    UUID c30Same = UUID.fromString("00000000-0000-0000-0000-000000000902");
    UUID c3Diff = UUID.fromString("00000000-0000-0000-0000-000000000903");
    UUID c30Diff = UUID.fromString("00000000-0000-0000-0000-000000000904");
    UUID cCold = UUID.fromString("00000000-0000-0000-0000-000000000905");
    UUID cObsZero = UUID.fromString("00000000-0000-0000-0000-000000000906");

    List<HistoricalCollaborationEvidence> hist = new ArrayList<>();
    // 3 vs 30 from same brand (ICC rho=0.50 -> nEff = 1.50 vs 1.9355, bounded by 2.0)
    for (int i = 0; i < 3; i++) hist.add(collab(c3Same, null, brand1, campId, 1.0, 1.0, 1.0, null, T1));
    for (int i = 0; i < 30; i++) hist.add(collab(c30Same, null, brand1, campId, 1.0, 1.0, 1.0, null, T1));
    // 3 vs 30 across different brands (3 brands vs 10 brands x 3)
    for (int i = 0; i < 3; i++) hist.add(collab(c3Diff, null, UUID.randomUUID(), campId, 1.0, 1.0, 1.0, null, T1));
    for (int i = 0; i < 30; i++) hist.add(collab(c30Diff, null, UUID.nameUUIDFromBytes(("b" + (i % 10)).getBytes()), campId, 1.0, 1.0, 1.0, null, T1));
    // Observed zero history vs cold start (0 collabs)
    hist.add(collab(cObsZero, null, brand1, campId, 0.0, null, null, null, T1));

    var snap = new ExtendedCampaignSnapshot(
        ExtendedCampaignSnapshot.SCHEMA_VERSION,
        new CampaignCreatorSnapshot(CampaignCreatorSnapshot.SCHEMA_VERSION, "s1", campId, "CONVERSION",
            List.of("TIKTOK"), List.of(), null, null, null, T_AS_OF, DataOrigin.REAL,
            List.of(obs(c3Same), obs(c30Same), obs(c3Diff), obs(c30Diff), obs(cCold), obs(cObsZero)), hist),
        brand1, List.of(), Map.of());
    var univ = universe(campId, List.of(c3Same, c30Same, c3Diff, c30Diff, cCold, cObsZero));

    var out = shrinkage.score(snap, univ, PriorMode.NEUTRAL_FIXED_050, false);
    var diag = out.diagnosticsByCreator();

    assertThat(diag.get(c3Same).nEffR()).isCloseTo(1.50, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(diag.get(c30Same).nEffR()).isLessThan(2.0);
    assertThat(diag.get(c3Diff).nEffR()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(diag.get(c30Diff).nEffR()).isCloseTo(15.0, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(diag.get(cCold).nEffR()).isZero();
    assertThat(diag.get(cObsZero).nEffR()).isEqualTo(1.0); // observed zero counts as 1 observation, not missing!
    assertThat(diag.get(cObsZero).nEffT()).isZero();
  }

  @Test
  void tests6To15_m4OverlapLeaveTargetOutFutureRejectionFixedWeightsAndDeterminism() {
    UUID campId = UUID.randomUUID();
    UUID brandId = UUID.randomUUID();
    UUID target = UUID.fromString("00000000-0000-0000-0000-000000000911");
    UUID cohort1 = UUID.fromString("00000000-0000-0000-0000-000000000912");
    UUID cohort2 = UUID.fromString("00000000-0000-0000-0000-000000000913");

    // 1 direct review for target + 1 review each from 2 cohort masters (so leave-target-out cohort has 2 reviews, 2 masters)
    // Plus 1 single-master cohort that lacks >= 2 distinct masters on INSTAGRAM
    List<HistoricalCollaborationEvidence> hist = List.of(
        collab(target, null, brandId, campId, 1.0, 1.0, 1.0, 1.0, T1),
        collab(cohort1, UUID.randomUUID(), brandId, campId, null, null, null, 0.25, T1),
        collab(cohort2, UUID.randomUUID(), brandId, campId, null, null, null, 0.25, T1));

    var snap = new ExtendedCampaignSnapshot(
        ExtendedCampaignSnapshot.SCHEMA_VERSION,
        new CampaignCreatorSnapshot(CampaignCreatorSnapshot.SCHEMA_VERSION, "m4", campId, "AWARENESS",
            List.of("TIKTOK"), List.of("BEAUTY"), null, null, null, T_AS_OF, DataOrigin.REAL,
            List.of(obs(target), obs(cohort1), obs(cohort2)), hist),
        brandId, List.of(new BrandPatternSpec("BEAUTY", "BEAUTY", "TIKTOK", 0.90)),
        Map.of(target, ext(target, "BEAUTY", 0.50), cohort1, ext(cohort1, "BEAUTY", 0.50), cohort2, ext(cohort2, "BEAUTY", 0.50)));
    var univ = universe(campId, List.of(target, cohort1, cohort2));

    var shrOut = shrinkage.score(snap, univ, PriorMode.EMPIRICAL_OBSERVED, false);
    var dTarget = shrOut.diagnosticsByCreator().get(target);
    assertThat(dTarget.directReviews()).isEqualTo(1);
    assertThat(dTarget.nicheCohortReviews()).isEqualTo(2); // leave-target-out: excludes target's own review!

    // Same fixed weights across all primary methods (useCritic = false)
    var wShrink = shrOut.result().weights();
    var wNeutral = ResearchBaselineScorers.create(BaselineMethod.NEUTRAL_IMPUTATION_BASELINE_V1, false, false).score(snap, univ).weights();
    var wAfr = ResearchBaselineScorers.create(BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1, false, false).score(snap, univ).weights();
    var wLegacy = ResearchBaselineScorers.create(BaselineMethod.LEGACY_REFERENCE_V1, false, false).score(snap, univ).weights();

    assertThat(wShrink.w1()).isEqualTo(wNeutral.w1()).isEqualTo(wAfr.w1()).isEqualTo(wLegacy.w1());
    assertThat(wShrink.w2()).isEqualTo(wNeutral.w2()).isEqualTo(wAfr.w2()).isEqualTo(wLegacy.w2());
    assertThat(wShrink.mode()).isEqualTo("BUSINESS_FALLBACK");

    // Future records rejected
    var futureSnap = new ExtendedCampaignSnapshot(
        ExtendedCampaignSnapshot.SCHEMA_VERSION,
        new CampaignCreatorSnapshot(CampaignCreatorSnapshot.SCHEMA_VERSION, "fut", campId, "AWARENESS",
            List.of("TIKTOK"), List.of(), null, null, null, T_AS_OF, DataOrigin.REAL,
            List.of(obs(target)), List.of(collab(target, null, brandId, campId, 1.0, 1.0, 1.0, 1.0, T_FUTURE))),
        brandId, List.of(), Map.of());
    assertThatThrownBy(() -> shrinkage.score(futureSnap, universe(campId, List.of(target)), PriorMode.EMPIRICAL_OBSERVED, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TEMPORAL_LEAKAGE");

    // Determinism & DGP-B negative control finite check
    var dgpB = new SyntheticDgpGenerator().generateExtended(new DgpConfig(
        DgpConfig.DGP_VERSION, DgpRegime.DGP_B, MissingnessMechanism.MNAR,
        MissingnessIntensity.HIGH, DatasetSplit.DEV, 33L, 15, T_AS_OF));
    var r1 = shrinkage.score(dgpB.extendedSnapshot(), dgpB.baseBundle().candidateUniverse(), PriorMode.EMPIRICAL_OBSERVED, false);
    var r2 = shrinkage.score(dgpB.extendedSnapshot(), dgpB.baseBundle().candidateUniverse(), PriorMode.EMPIRICAL_OBSERVED, false);
    assertThat(r1.result()).isEqualTo(r2.result());
    assertThat(r1.result().mainRanked()).allMatch(s -> Double.isFinite(s.matchScore()) && s.sm1() >= 0.0 && s.sm1() <= 1.0 && s.sm4() >= 0.0 && s.sm4() <= 1.0);
  }

  private CandidateUniverse universe(UUID campId, List<UUID> ids) {
    return new CandidateUniverse(CandidateUniverse.SCHEMA_VERSION, campId, "u1", DataOrigin.REAL, "TEST",
        ids.stream().map(id -> new CandidateEntry(id, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of())).toList());
  }

  private ObservedEvidenceRecord obs(UUID cid) {
    var v = MetricObservation.observed(200.0, MetricUnit.COUNT, 2, 2, "T", T0, T1);
    var l = MetricObservation.observed(10.0, MetricUnit.COUNT, 2, 2, "T", T0, T1);
    return new ObservedEvidenceRecord(ObservedEvidenceRecord.SCHEMA_VERSION, cid, null, "TIKTOK", DataOrigin.REAL, "s1", T0, T1,
        MetricObservation.observed(1_000.0, MetricUnit.COUNT, 1, 1, "T", T0, T1), v, l, null, null,
        MetricObservation.observed(0.05, MetricUnit.DECIMAL_RATIO, 2, 2, "T", T0, T1),
        List.of(new PostObservation("p1", T0.minusSeconds(120), v, l, null, null),
                new PostObservation("p2", T0.minusSeconds(60), v, l, null, null)));
  }

  private ObservedCreatorContextExtension ext(UUID cid, String niche, double cosine) {
    return new ObservedCreatorContextExtension(
        ObservedCreatorContextExtension.SCHEMA_VERSION, cid, DataOrigin.REAL, niche, niche,
        MetricObservation.observed(cosine, MetricUnit.COSINE_SIMILARITY, 1, 1, "E", T0, T1), T0, T1);
  }

  private HistoricalCollaborationEvidence collab(
      UUID cid, UUID mid, UUID bid, UUID campId, Double r, Double t, Double k, Double rev, Instant knownAt) {
    return new HistoricalCollaborationEvidence(
        HistoricalCollaborationEvidence.SCHEMA_VERSION, UUID.randomUUID(), cid, mid, bid, campId,
        DataOrigin.REAL, T0, knownAt, 1, "COMPLETED",
        r != null ? MetricObservation.observed(r, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, knownAt) : null,
        t != null ? MetricObservation.observed(t, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, knownAt) : null,
        k != null ? MetricObservation.observed(k, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, knownAt) : null,
        rev != null ? MetricObservation.observed(rev, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, knownAt) : null);
  }
}
