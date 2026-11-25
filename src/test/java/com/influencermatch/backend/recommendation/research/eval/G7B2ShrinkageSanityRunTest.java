package com.influencermatch.backend.recommendation.research.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.MethodWorldMetrics;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.PairedWorldEvaluationReport;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.ResearchScorer;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.RunStatus;
import com.influencermatch.backend.recommendation.research.eval.PilotRunner.PilotWorldSpec;
import com.influencermatch.backend.recommendation.research.scorer.EvidenceConditionedShrinkageScorer;
import com.influencermatch.backend.recommendation.research.scorer.EvidenceConditionedShrinkageScorer.PriorMode;
import com.influencermatch.backend.recommendation.research.scorer.ResearchBaselineScorers;
import com.influencermatch.backend.recommendation.research.scorer.ResearchBaselineScorers.BaselineMethod;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class G7B2ShrinkageSanityRunTest {

  private static final Instant START_AS_OF = Instant.parse("2026-09-01T00:00:00Z");
  private static final List<Long> PILOT_SEEDS = List.of(9101L, 9102L, 9103L);

  @Test
  void pairedSanityRunWithFixedWeightsIncludesShrinkageAndNeutralPriorSensitivity() {
    PilotRunner pilot = new PilotRunner();
    List<ResearchScorer> scorers = List.of(
        ResearchBaselineScorers.create(BaselineMethod.LEGACY_REFERENCE_V1, false, false),
        ResearchBaselineScorers.create(BaselineMethod.STRICT_GATING_BASELINE_V1, false, false),
        ResearchBaselineScorers.create(BaselineMethod.NEUTRAL_IMPUTATION_BASELINE_V1, false, false),
        ResearchBaselineScorers.create(BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1, false, false),
        EvidenceConditionedShrinkageScorer.asResearchScorer(PriorMode.EMPIRICAL_OBSERVED, false),
        EvidenceConditionedShrinkageScorer.asResearchScorer(PriorMode.NEUTRAL_FIXED_050, false));

    for (DgpRegime regime : List.of(DgpRegime.DGP_A, DgpRegime.DGP_B)) {
      for (MissingnessMechanism mech : List.of(MissingnessMechanism.MCAR, MissingnessMechanism.MNAR)) {
        for (MissingnessIntensity intensity : List.of(MissingnessIntensity.LOW, MissingnessIntensity.HIGH)) {
          List<PairedWorldEvaluationReport> reps = new ArrayList<>();
          for (long seed : PILOT_SEEDS) {
            reps.addAll(pilot.runPilotWorld(
                new PilotWorldSpec(regime, mech, intensity, seed, 3, 40, 6, 8, START_AS_OF), scorers));
          }
          assertThat(reps).hasSize(24);
          for (int i = 0; i < scorers.size(); i++) {
            int idx = i;
            List<MethodWorldMetrics> mm = reps.stream().map(r -> r.methodMetrics().get(idx)).toList();
            assertThat(mm).allMatch(m -> m.status() != RunStatus.FAILED);
            double ndcg = mm.stream().mapToDouble(MethodWorldMetrics::ndcgAt10).average().orElse(0.0);
            double regret = mm.stream().mapToDouble(MethodWorldMetrics::expectedUtilityRegretAt10).average().orElse(0.0);
            double candCov = mm.stream().mapToDouble(MethodWorldMetrics::candidateScoringCoverage).average().orElse(0.0);
            double fill = mm.stream().mapToDouble(MethodWorldMetrics::listFillRateAt10).average().orElse(0.0);
            long mainSum = mm.stream().mapToLong(MethodWorldMetrics::mainCount).sum();
            long univSum = reps.stream().mapToLong(r -> r.universeCreatorIds().size()).sum();
            System.out.printf(
                "G7B2_SANITY[%s|%s|%s|%s]: nDCG@10=%.4f, regret=%.4f, candCov=%.3f, mainCov=%.3f, fill=%.3f%n",
                regime, mech, intensity, scorers.get(idx).scorerVersion(),
                ndcg, regret, candCov, (double) mainSum / univSum, fill);
          }
        }
      }
    }
  }
}
