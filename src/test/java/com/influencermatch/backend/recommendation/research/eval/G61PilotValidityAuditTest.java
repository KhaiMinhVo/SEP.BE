package com.influencermatch.backend.recommendation.research.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse.CandidateEntry;
import com.influencermatch.backend.recommendation.research.contracts.ExperimentRunManifest;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.OracleGroundTruthRecord;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.OracleGradeSource;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.PairedWorldEvaluationReport;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.ResearchScorer;
import com.influencermatch.backend.recommendation.research.eval.PilotRunner.PilotWorldSpec;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.OfflineScoringResult;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.PolicyVariant;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.ScoredCandidateRecord;
import com.influencermatch.backend.recommendation.research.validation.ResearchJsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class G61PilotValidityAuditTest {

  private static final Instant START_AS_OF = Instant.parse("2026-09-01T00:00:00Z");
  private static final List<Long> PILOT_SEEDS = List.of(9101L, 9102L, 9103L);

  @Test
  void audit1_shortListRegretMustPenalizeMissingSlotsAndIneligibleMustNotEnterIdcg() {
    UUID eligible1 = UUID.randomUUID();
    UUID eligible2 = UUID.randomUUID();
    UUID ineligibleHigh = UUID.randomUUID();
    UUID campId = UUID.randomUUID();

    // Bug 1: short-list of size 1 with top creator (0.90) vs K=2 (eligible2=0.70) must have positive regret, not 0.0
    double regretShort = EvaluationMetrics.expectedUtilityRegretAtK(
        List.of(eligible1), Map.of(eligible1, 0.90, eligible2, 0.70), 2);
    assertThat(regretShort).isGreaterThan(0.20);

    // Bug 2: ineligible creator with utility 0.99 must NOT inflate IDCG in ExperimentRunner
    var baseSnap = new CampaignCreatorSnapshot(
        CampaignCreatorSnapshot.SCHEMA_VERSION, "s1", campId, "AWARENESS",
        List.of("TIKTOK"), List.of(), null, null, null, START_AS_OF, DataOrigin.SYN, List.of(), List.of());
    var extSnap = new ExtendedCampaignSnapshot(ExtendedCampaignSnapshot.SCHEMA_VERSION, baseSnap, null, List.of(), Map.of());
    var universe = new CandidateUniverse(
        CandidateUniverse.SCHEMA_VERSION, campId, "s1", DataOrigin.SYN, "TEST",
        List.of(
            new CandidateEntry(eligible1, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of()),
            new CandidateEntry(eligible2, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of()),
            new CandidateEntry(ineligibleHigh, null, "INSTAGRAM", HardEligibilityStatus.INELIGIBLE, List.of("PLATFORM_MISMATCH"))));
    var oracles = List.of(
        new OracleGroundTruthRecord(OracleGroundTruthRecord.SCHEMA_VERSION, campId, eligible1, DataOrigin.SYN,
            OracleGradeSource.SYNTHETIC_EXPECTED_UTILITY, START_AS_OF, 0.80, null, null, true, false, ""),
        new OracleGroundTruthRecord(OracleGroundTruthRecord.SCHEMA_VERSION, campId, eligible2, DataOrigin.SYN,
            OracleGradeSource.SYNTHETIC_EXPECTED_UTILITY, START_AS_OF, 0.40, null, null, true, false, ""),
        new OracleGroundTruthRecord(OracleGroundTruthRecord.SCHEMA_VERSION, campId, ineligibleHigh, DataOrigin.SYN,
            OracleGradeSource.SYNTHETIC_EXPECTED_UTILITY, START_AS_OF, 0.99, null, null, false, false, ""));

    ResearchScorer idealEligibleScorer = new ResearchScorer() {
      @Override public String scorerVersion() { return "ideal-eligible"; }
      @Override public OfflineScoringResult score(ExtendedCampaignSnapshot s, CandidateUniverse u) {
        var w = new EngineWeights(0.25, 0.25, 0.25, 0.25, "BUSINESS_FALLBACK", 0.0, null, new double[]{0.25, 0.25, 0.25, 0.25});
        return new OfflineScoringResult("ideal-eligible", "v1", "s1", campId, START_AS_OF, DataOrigin.SYN,
            List.of(eligible1, eligible2, ineligibleHigh), w,
            List.of(
                new ScoredCandidateRecord(eligible1, "MAIN", 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 80.0, "LOW", "PRIOR/NEUTRAL_FALLBACK", false, "NONE", List.of()),
                new ScoredCandidateRecord(eligible2, "MAIN", 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 40.0, "LOW", "PRIOR/NEUTRAL_FALLBACK", false, "NONE", List.of())),
            List.of(), List.of());
      }
    };
    var manifest = new ExperimentRunManifest(ExperimentRunManifest.SCHEMA_VERSION, UUID.randomUUID(),
        "p.v1", "d.v1", "f.v1", "ideal-eligible", "c", "d", "seed=1", DataOrigin.SYN, START_AS_OF, Map.of());
    var report = new ExperimentRunner().evaluateWorld(manifest, extSnap, universe, oracles, List.of(idealEligibleScorer));
    assertThat(report.methodMetrics().getFirst().ndcgAt10()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
  }

  @Test
  void audit2_gateOnOffComparisonEightConditions24PairedWorldsAndTemporalVerification() {
    PilotRunner pilot = new PilotRunner();

    // 1. Gate ON vs Gate OFF comparison on DGP_A | MCAR | LOW (3 paired seeds)
    for (boolean gate : new boolean[] { false, true }) {
      List<PairedWorldEvaluationReport> reps = new ArrayList<>();
      for (long seed : PILOT_SEEDS) {
        reps.addAll(pilot.runPilotWorld(
            new PilotWorldSpec(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, seed, 3, 40, 6, 8, START_AS_OF),
            gate));
      }
      printConditionSummary("GATE=" + gate, DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, reps);
    }

    // 2. All 8 conditions (2 DGP x 2 Mech x 2 Intensity x 3 paired seeds = 24 paired worlds, 192 queries)
    for (DgpRegime regime : List.of(DgpRegime.DGP_A, DgpRegime.DGP_B)) {
      for (MissingnessMechanism mech : List.of(MissingnessMechanism.MCAR, MissingnessMechanism.MNAR)) {
        for (MissingnessIntensity intensity : List.of(MissingnessIntensity.LOW, MissingnessIntensity.HIGH)) {
          List<PairedWorldEvaluationReport> reps = new ArrayList<>();
          for (long seed : PILOT_SEEDS) {
            reps.addAll(pilot.runPilotWorld(
                new PilotWorldSpec(regime, mech, intensity, seed, 3, 40, 6, 8, START_AS_OF), false));
          }
          assertThat(reps).hasSize(24);
          printConditionSummary("PAIRED_8COND", regime, mech, intensity, reps);
        }
      }
    }

    // 3. Temporal snapshot verification across 6 periods (qIdx 0..5 correspond to periods 0..5)
    var temporalReps = pilot.runPilotWorld(
        new PilotWorldSpec(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, 9101L, 3, 40, 6, 8, START_AS_OF),
        false);
    Instant prevAsOf = Instant.MIN;
    for (int p = 0; p < 6; p++) {
      Instant curAsOf = temporalReps.get(p).manifest().scoringAsOf();
      assertThat(curAsOf).isAfter(prevAsOf);
      prevAsOf = curAsOf;
    }

    // 4. Determinism check
    var spec = new PilotWorldSpec(DgpRegime.DGP_B, MissingnessMechanism.MNAR, MissingnessIntensity.HIGH, 9102L, 3, 40, 6, 8, START_AS_OF);
    assertThat(ResearchJsonMapper.toDeterministicJson(pilot.runPilotWorld(spec, true)))
        .isEqualTo(ResearchJsonMapper.toDeterministicJson(pilot.runPilotWorld(spec, true)));
  }

  private void printConditionSummary(
      String tag, DgpRegime regime, MissingnessMechanism mech, MissingnessIntensity intensity,
      List<PairedWorldEvaluationReport> reps) {
    long mainSum = 0, provSum = 0, exclSum = 0, univSum = 0, retSum = 0, fullKQueries = 0;
    for (var r : reps) {
      var m = r.methodMetrics().getFirst();
      mainSum += m.mainCount();
      provSum += m.provisionalCount();
      exclSum += m.excludedCount();
      univSum += r.universeCreatorIds().size();
      retSum += Math.min(10, m.mainCount());
      if (m.mainCount() >= 10) fullKQueries++;
    }
    double n = reps.size();
    double meanNdcg = reps.stream().mapToDouble(r -> r.methodMetrics().getFirst().ndcgAt10()).average().orElse(0.0);
    double meanRegret = reps.stream().mapToDouble(r -> r.methodMetrics().getFirst().expectedUtilityRegretAt10()).average().orElse(0.0);
    double meanFill = reps.stream().mapToDouble(r -> r.methodMetrics().getFirst().listFillRateAt10()).average().orElse(0.0);
    System.out.printf(
        "%s[%s|%s|%s]: q=%d, univ=%d, scored=%d, main=%d, prov=%d, excl=%d, ret=%d, candCov=%.3f, mainCov=%.3f, fullKQ=%.3f, fill=%.3f, nDCG@10=%.4f, regret=%.4f%n",
        tag, regime, mech, intensity, reps.size(), univSum, mainSum + provSum, mainSum, provSum, exclSum, retSum,
        (double) (mainSum + provSum) / univSum, (double) mainSum / univSum, fullKQueries / n, meanFill, meanNdcg, meanRegret);
  }
}
