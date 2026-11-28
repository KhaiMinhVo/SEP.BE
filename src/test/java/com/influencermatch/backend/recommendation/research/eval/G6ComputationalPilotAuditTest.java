package com.influencermatch.backend.recommendation.research.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.PairedWorldEvaluationReport;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.RunStatus;
import com.influencermatch.backend.recommendation.research.eval.PilotRunner.PilotWorldSpec;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.OfflineScoringResult;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.ScoredCandidateRecord;
import com.influencermatch.backend.recommendation.research.validation.ResearchJsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class G6ComputationalPilotAuditTest {

  private static final Instant START_AS_OF = Instant.parse("2026-09-01T00:00:00Z");
  private static final List<Long> PILOT_SEEDS = List.of(9101L, 9102L, 9103L);

  @Test
  void execute12PilotWorldsAndAuditDiagnosticsAndDeterminism() {
    PilotRunner pilot = new PilotRunner();

    for (DgpRegime regime : List.of(DgpRegime.DGP_A, DgpRegime.DGP_B)) {
      for (MissingnessMechanism mech : List.of(MissingnessMechanism.MCAR, MissingnessMechanism.MNAR)) {
        long tStart = System.nanoTime();
        List<PairedWorldEvaluationReport> conditionReports = new ArrayList<>();
        for (long seed : PILOT_SEEDS) {
          var spec = new PilotWorldSpec(regime, mech, MissingnessIntensity.LOW, seed, 3, 40, 6, 8, START_AS_OF);
          List<PairedWorldEvaluationReport> worldReports = pilot.runPilotWorld(spec);
          assertThat(worldReports).hasSize(8);
          conditionReports.addAll(worldReports);
        }
        long elapsedMs = (System.nanoTime() - tStart) / 1_000_000L;

        // Verify >= 10 candidates per query and no FAILED/EMPTY runs
        for (var rep : conditionReports) {
          assertThat(rep.universeCreatorIds().size()).isGreaterThanOrEqualTo(10);
          var m = rep.methodMetrics().getFirst();
          assertThat(m.status()).isEqualTo(RunStatus.SUCCESS);
          assertThat(m.mainCount()).isGreaterThanOrEqualTo(10);
          assertThat(m.ndcgAt10()).isBetween(0.0, 1.0);
        }

        // Aggregate diagnostics
        List<ScoredCandidateRecord> allScored = new ArrayList<>();
        long totalExcluded = 0, totalUniverse = 0, totalReturned = 0, totalLossy = 0;
        Map<String, Long> m4Tiers = new LinkedHashMap<>();
        long m1Fallback = 0, m3Fallback = 0;
        for (var rep : conditionReports) {
          OfflineScoringResult res = rep.rawScorerOutputs().values().iterator().next();
          allScored.addAll(res.mainRanked());
          allScored.addAll(res.provisionalRanked());
          totalExcluded += res.excluded().size();
          totalUniverse += res.universeCreatorIds().size();
          totalReturned += Math.min(10, res.mainRanked().size());
          for (var s : res.mainRanked()) {
            if (!"AVAILABLE".equals(s.m1Availability())) m1Fallback++;
            if (!s.hasSemanticEmbedding()) m3Fallback++;
            m4Tiers.merge(s.m4Source(), 1L, Long::sum);
            totalLossy += s.lossyMappings().size();
          }
          for (var s : res.provisionalRanked()) {
            if (!"AVAILABLE".equals(s.m1Availability())) m1Fallback++;
            if (!s.hasSemanticEmbedding()) m3Fallback++;
            m4Tiers.merge(s.m4Source(), 1L, Long::sum);
            totalLossy += s.lossyMappings().size();
          }
        }

        double meanSm1 = allScored.stream().mapToDouble(ScoredCandidateRecord::sm1).average().orElse(0.0);
        double meanSm2 = allScored.stream().mapToDouble(ScoredCandidateRecord::sm2).average().orElse(0.0);
        double meanSm3 = allScored.stream().mapToDouble(ScoredCandidateRecord::sm3).average().orElse(0.0);
        double meanSm4 = allScored.stream().mapToDouble(ScoredCandidateRecord::sm4).average().orElse(0.0);
        double meanNdcg = conditionReports.stream().mapToDouble(r -> r.methodMetrics().getFirst().ndcgAt10()).average().orElse(0.0);
        double meanRegret = conditionReports.stream().mapToDouble(r -> r.methodMetrics().getFirst().expectedUtilityRegretAt10()).average().orElse(0.0);
        double meanFill = conditionReports.stream().mapToDouble(r -> r.methodMetrics().getFirst().listFillRateAt10()).average().orElse(0.0);
        double meanCandCov = conditionReports.stream().mapToDouble(r -> r.methodMetrics().getFirst().candidateScoringCoverage()).average().orElse(0.0);
        double meanMainCov = conditionReports.stream()
            .mapToDouble(r -> (double) r.methodMetrics().getFirst().mainCount() / r.universeCreatorIds().size())
            .average().orElse(0.0);

        System.out.printf(
            "PILOT_DIAG[%s|%s]: worlds=3, queries=%d, meanSM=(%.3f, %.3f, %.3f, %.3f), m1Fallback=%.3f, m3Fallback=%.3f, m4Tiers=%s, " +
            "scored=%d, excluded=%d, universe=%d, returned=%d, nDCG@10=%.4f, regret=%.4f, fill=%.3f, candCov=%.3f, mainCov=%.3f, lossy=%d, ms=%d%n",
            regime, mech, conditionReports.size(), meanSm1, meanSm2, meanSm3, meanSm4,
            (double) m1Fallback / allScored.size(), (double) m3Fallback / allScored.size(), m4Tiers,
            allScored.size(), totalExcluded, totalUniverse, totalReturned,
            meanNdcg, meanRegret, meanFill, meanCandCov, meanMainCov, totalLossy, elapsedMs);
      }
    }

    // Determinism check across repeated runs
    var spec = new PilotWorldSpec(DgpRegime.DGP_A, MissingnessMechanism.MNAR, MissingnessIntensity.LOW, 9101L, 3, 40, 6, 8, START_AS_OF);
    var run1 = pilot.runPilotWorld(spec);
    var run2 = pilot.runPilotWorld(spec);
    assertThat(ResearchJsonMapper.toDeterministicJson(run1.getFirst().rawScorerOutputs()))
        .isEqualTo(ResearchJsonMapper.toDeterministicJson(run2.getFirst().rawScorerOutputs()));
  }
}
