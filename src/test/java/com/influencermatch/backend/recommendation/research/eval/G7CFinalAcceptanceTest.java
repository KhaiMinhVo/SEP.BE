package com.influencermatch.backend.recommendation.research.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse.CandidateEntry;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedCreatorContextExtension;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricUnit;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.ResearchScorer;
import com.influencermatch.backend.recommendation.research.eval.PilotRunner.PilotWorldSpec;
import com.influencermatch.backend.recommendation.research.scorer.CommonResearchFeaturePreparer;
import com.influencermatch.backend.recommendation.research.scorer.EvidenceConditionedShrinkageScorer;
import com.influencermatch.backend.recommendation.research.scorer.EvidenceConditionedShrinkageScorer.PriorMode;
import com.influencermatch.backend.recommendation.research.scorer.ResearchBaselineScorers;
import com.influencermatch.backend.recommendation.research.scorer.ResearchBaselineScorers.BaselineMethod;
import com.influencermatch.backend.recommendation.research.validation.ResearchJsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class G7CFinalAcceptanceTest {

  private static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant T_AS_OF = Instant.parse("2026-11-25T00:00:00Z");

  @Test
  void commonM2M3FeatureParityAndMissingErDerivationWorkIdenticallyAcrossPrimaryMethods() {
    UUID campId = UUID.randomUUID();
    UUID cid = UUID.fromString("00000000-0000-0000-0000-000000000951");

    // engagementRate is MISSING, but avgLikes=50 and avgViews=1000 -> derived ER = 0.05 (BY_VIEWS)
    var obs = new ObservedEvidenceRecord(
        ObservedEvidenceRecord.SCHEMA_VERSION, cid, null, "TIKTOK", DataOrigin.REAL, "g7c", T0, T1,
        MetricObservation.observed(2_000.0, MetricUnit.COUNT, 1, 1, "T", T0, T1),
        MetricObservation.observed(1_000.0, MetricUnit.COUNT, 2, 2, "T", T0, T1),
        MetricObservation.observed(50.0, MetricUnit.COUNT, 2, 2, "T", T0, T1),
        null, null,
        MetricObservation.missing(MetricUnit.DECIMAL_RATIO, 2, "T", T0, T1),
        List.of());
    var ext = new ObservedCreatorContextExtension(
        ObservedCreatorContextExtension.SCHEMA_VERSION, cid, DataOrigin.REAL, "BEAUTY", "BEAUTY",
        MetricObservation.observed(0.60, MetricUnit.COSINE_SIMILARITY, 1, 1, "E", T0, T1), T0, T1);

    var prep = CommonResearchFeaturePreparer.prepare(obs, ext);
    assertThat(prep.rawEr()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-12));
    assertThat(prep.erNorm()).isCloseTo(0.50, org.assertj.core.data.Offset.offset(1e-12));
    assertThat(prep.vrNorm()).isCloseTo(0.50, org.assertj.core.data.Offset.offset(1e-12)); // 1000/2000=0.50, K_VR=0.50
    assertThat(prep.sm3Norm()).isCloseTo(0.80, org.assertj.core.data.Offset.offset(1e-12));

    var snap = new ExtendedCampaignSnapshot(
        ExtendedCampaignSnapshot.SCHEMA_VERSION,
        new CampaignCreatorSnapshot(CampaignCreatorSnapshot.SCHEMA_VERSION, "g7c", campId, "AWARENESS",
            List.of("TIKTOK"), List.of("BEAUTY"), null, null, null, T_AS_OF, DataOrigin.REAL,
            List.of(obs), List.of()),
        null, List.of(), Map.of(cid, ext));
    var univ = new CandidateUniverse(CandidateUniverse.SCHEMA_VERSION, campId, "g7c", DataOrigin.REAL, "TEST",
        List.of(new CandidateEntry(cid, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of())));

    var neutralRes = ResearchBaselineScorers.create(BaselineMethod.NEUTRAL_IMPUTATION_BASELINE_V1, false, false).score(snap, univ);
    var afrRes = ResearchBaselineScorers.create(BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1, false, false).score(snap, univ);
    var shrinkRes = EvidenceConditionedShrinkageScorer.asResearchScorer(PriorMode.NEUTRAL_FIXED_050, false).score(snap, univ);

    // Identical M2 (erNorm, vrNorm) and M3 (sm3) inputs across all primary methods
    // Note: without posts for STAB, shrinkage routes to PROVISIONAL while NEUTRAL/AFR keep in MAIN
    var shrinkRec = shrinkRes.provisionalRanked().getFirst();
    assertThat(neutralRes.mainRanked().getFirst().erNorm())
        .isEqualTo(afrRes.mainRanked().getFirst().erNorm())
        .isEqualTo(shrinkRec.erNorm());
    assertThat(neutralRes.mainRanked().getFirst().vrNorm())
        .isEqualTo(afrRes.mainRanked().getFirst().vrNorm())
        .isEqualTo(shrinkRec.vrNorm());
    assertThat(neutralRes.mainRanked().getFirst().sm3())
        .isEqualTo(afrRes.mainRanked().getFirst().sm3())
        .isEqualTo(shrinkRec.sm3());

    // All 4 weights w1, w2, w3, w4 must be identical across all primary methods
    var wN = neutralRes.weights();
    var wA = afrRes.weights();
    var wS = shrinkRes.weights();
    assertThat(List.of(wN.w1(), wN.w2(), wN.w3(), wN.w4()))
        .isEqualTo(List.of(wA.w1(), wA.w2(), wA.w3(), wA.w4()))
        .isEqualTo(List.of(wS.w1(), wS.w2(), wS.w3(), wS.w4()));
  }

  @Test
  void mainSeedsAreDisjointFromPilotSeedsAndTinySanityRunIsDeterministicWithNoNan() {
    Set<Long> pilotSeeds = Set.of(9101L, 9102L, 9103L);
    List<Long> mainSeeds = java.util.stream.LongStream.rangeClosed(10001L, 10030L).boxed().toList();
    assertThat(mainSeeds).hasSize(30).doesNotContainAnyElementsOf(pilotSeeds);

    PilotRunner pilot = new PilotRunner();
    List<ResearchScorer> primaryMethods = List.of(
        ResearchBaselineScorers.create(BaselineMethod.STRICT_GATING_BASELINE_V1, false, false),
        ResearchBaselineScorers.create(BaselineMethod.NEUTRAL_IMPUTATION_BASELINE_V1, false, false),
        ResearchBaselineScorers.create(BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1, false, false),
        EvidenceConditionedShrinkageScorer.asResearchScorer(PriorMode.EMPIRICAL_OBSERVED, false),
        EvidenceConditionedShrinkageScorer.asResearchScorer(PriorMode.NEUTRAL_FIXED_050, false));

    var spec = new PilotWorldSpec(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.HIGH, 9101L, 3, 20, 4, 4, T_AS_OF);
    var run1 = pilot.runPilotWorld(spec, primaryMethods);
    var run2 = pilot.runPilotWorld(spec, primaryMethods);

    assertThat(ResearchJsonMapper.toDeterministicJson(run1))
        .isEqualTo(ResearchJsonMapper.toDeterministicJson(run2));
    for (var rep : run1) {
      assertThat(rep.rawScorerOutputs().values()).allSatisfy(out -> {
        assertThat(out.mainRanked()).allMatch(r -> Double.isFinite(r.matchScore()));
        assertThat(out.provisionalRanked()).allMatch(r -> Double.isFinite(r.matchScore()));
      });
    }
  }
}
