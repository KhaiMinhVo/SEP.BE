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
import com.influencermatch.backend.recommendation.research.scorer.ResearchBaselineScorers;
import com.influencermatch.backend.recommendation.research.scorer.ResearchBaselineScorers.BaselineMethod;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class G7ABaselinesSanityRunTest {

  private static final Instant START_AS_OF = Instant.parse("2026-09-01T00:00:00Z");
  private static final List<Long> PILOT_SEEDS = List.of(9101L, 9102L, 9103L);

  @Test
  void pairedSanityRunAcrossEightConditionsComparesAllFourBaselines() {
    PilotRunner pilot = new PilotRunner();
    List<ResearchScorer> scorers = List.of(
        ResearchBaselineScorers.create(BaselineMethod.LEGACY_REFERENCE_V1, false),
        ResearchBaselineScorers.create(BaselineMethod.STRICT_GATING_BASELINE_V1, false),
        ResearchBaselineScorers.create(BaselineMethod.NEUTRAL_IMPUTATION_BASELINE_V1, false),
        ResearchBaselineScorers.create(BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1, false));

    for (DgpRegime regime : List.of(DgpRegime.DGP_A, DgpRegime.DGP_B)) {
      for (MissingnessMechanism mech : List.of(MissingnessMechanism.MCAR, MissingnessMechanism.MNAR)) {
        for (MissingnessIntensity intensity : List.of(MissingnessIntensity.LOW, MissingnessIntensity.HIGH)) {
          List<PairedWorldEvaluationReport> conditionReps = new ArrayList<>();
          for (long seed : PILOT_SEEDS) {
            var spec = new PilotWorldSpec(regime, mech, intensity, seed, 3, 40, 6, 8, START_AS_OF);
            conditionReps.addAll(pilot.runPilotWorld(spec, scorers));
          }
          assertThat(conditionReps).hasSize(24);

          for (int mIdx = 0; mIdx < scorers.size(); mIdx++) {
            int idx = mIdx;
            List<MethodWorldMetrics> mm = conditionReps.stream().map(r -> r.methodMetrics().get(idx)).toList();
            assertThat(mm).allMatch(m -> m.status() != RunStatus.FAILED);
            double ndcg = mm.stream().mapToDouble(MethodWorldMetrics::ndcgAt10).average().orElse(0.0);
            double regret = mm.stream().mapToDouble(MethodWorldMetrics::expectedUtilityRegretAt10).average().orElse(0.0);
            double candCov = mm.stream().mapToDouble(MethodWorldMetrics::candidateScoringCoverage).average().orElse(0.0);
            double fill = mm.stream().mapToDouble(MethodWorldMetrics::listFillRateAt10).average().orElse(0.0);
            long mainSum = mm.stream().mapToLong(MethodWorldMetrics::mainCount).sum();
            long univSum = conditionReps.stream().mapToLong(r -> r.universeCreatorIds().size()).sum();
            long fullK = mm.stream().filter(m -> m.mainCount() >= 10).count();

            System.out.printf(
                "G7A_SANITY[%s|%s|%s|%s]: nDCG@10=%.4f, regret=%.4f, candCov=%.3f, mainCov=%.3f, fullKQ=%.3f, fill=%.3f%n",
                regime, mech, intensity, scorers.get(idx).scorerVersion(),
                ndcg, regret, candCov, (double) mainSum / univSum, fullK / 24.0, fill);
          }
        }
      }
    }
  }
}
