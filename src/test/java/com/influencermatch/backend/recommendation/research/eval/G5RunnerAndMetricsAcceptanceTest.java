package com.influencermatch.backend.recommendation.research.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DatasetSplit;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.dgp.SyntheticDgpGenerator;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.ResearchScorer;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.RunStatus;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.OfflineScoringResult;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.PolicyVariant;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.ScoredCandidateRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class G5RunnerAndMetricsAcceptanceTest {

  private static final Instant T_AS_OF = Instant.parse("2026-11-25T00:00:00Z");

  @Test
  void metricsHandleZeroIdcgKGreaterThanCountAndShortListPadding() {
    UUID c1 = UUID.randomUUID();
    UUID c2 = UUID.randomUUID();

    // Zero-IDCG returns 0.0 (never NaN)
    assertThat(EvaluationMetrics.ndcgAtK(List.of(c1, c2), Map.of(c1, 0.0, c2, 0.0), 10)).isZero();

    // K > candidate count: ideal order gives 1.0
    assertThat(EvaluationMetrics.ndcgAtK(List.of(c1, c2), Map.of(c1, 0.9, c2, 0.4), 10))
        .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));

    // Regret = 0.0 when ranked in ideal order
    assertThat(EvaluationMetrics.expectedUtilityRegretAtK(List.of(c1, c2), Map.of(c1, 0.9, c2, 0.4), 10))
        .isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-12));

    // Short-list padding & fill rate
    assertThat(EvaluationMetrics.listFillRateAtK(2, 10)).isEqualTo(0.20);
    assertThat(EvaluationMetrics.padShortListToK(List.of(c1, c2), 10)).hasSize(10).containsNull();
  }

  @Test
  void runnerEvaluatesPairedWorldAndExplicitlyRecordsEmptyFailedAndUniverseViolations() {
    var extBundle = new SyntheticDgpGenerator().generateExtended(new DgpConfig(
        DgpConfig.DGP_VERSION, DgpRegime.DGP_A, MissingnessMechanism.MCAR,
        MissingnessIntensity.LOW, DatasetSplit.DEV, 88L, 12, T_AS_OF));

    var legacyEngine = new OfflineLegacyFourMemoryScorer();
    ResearchScorer validScorer = new ResearchScorer() {
      @Override
      public String scorerVersion() { return "corrected-reference-v1"; }

      @Override
      public OfflineScoringResult score(ExtendedCampaignSnapshot snapshot, CandidateUniverse universe) {
        return legacyEngine.score(snapshot, universe, PolicyVariant.CORRECTED_REFERENCE_V1, false);
      }
    };
    ResearchScorer emptyScorer = new ResearchScorer() {
      @Override
      public String scorerVersion() { return "empty-scorer-v0"; }

      @Override
      public OfflineScoringResult score(ExtendedCampaignSnapshot snapshot, CandidateUniverse universe) {
        return new OfflineScoringResult("empty-scorer-v0", "v1", snapshot.baseSnapshot().snapshotId(),
            snapshot.baseSnapshot().campaignId(), T_AS_OF, snapshot.baseSnapshot().dataOrigin(),
            List.of(), new EngineWeights(0.25, 0.25, 0.25, 0.25, "BUSINESS_FALLBACK", 0.0, null, new double[]{0.25, 0.25, 0.25, 0.25}),
            List.of(), List.of(), List.of());
      }
    };
    ResearchScorer rogueUniverseScorer = new ResearchScorer() {
      @Override
      public String scorerVersion() { return "rogue-outside-universe"; }

      @Override
      public OfflineScoringResult score(ExtendedCampaignSnapshot snapshot, CandidateUniverse universe) {
        UUID outsider = UUID.randomUUID();
        var rec = new ScoredCandidateRecord(outsider, "MAIN", 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 50.0,
            "LOW", "PRIOR/NEUTRAL_FALLBACK", false, "NONE", List.of());
        return new OfflineScoringResult("rogue-outside-universe", "v1", snapshot.baseSnapshot().snapshotId(),
            snapshot.baseSnapshot().campaignId(), T_AS_OF, snapshot.baseSnapshot().dataOrigin(),
            List.of(outsider), new EngineWeights(0.25, 0.25, 0.25, 0.25, "BUSINESS_FALLBACK", 0.0, null, new double[]{0.25, 0.25, 0.25, 0.25}),
            List.of(rec), List.of(), List.of());
      }
    };

    var report = new ExperimentRunner().evaluateWorld(
        extBundle.baseBundle().manifest(),
        extBundle.extendedSnapshot(),
        extBundle.baseBundle().candidateUniverse(),
        extBundle.baseBundle().oracleGroundTruth(),
        List.of(validScorer, emptyScorer, rogueUniverseScorer));

    assertThat(report.methodMetrics()).hasSize(3);
    assertThat(report.methodMetrics().get(0).status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(report.methodMetrics().get(0).ndcgAt10()).isBetween(0.0, 1.0);
    assertThat(report.methodMetrics().get(1).status()).isEqualTo(RunStatus.EMPTY_RANKING);
    assertThat(report.methodMetrics().get(2).status()).isEqualTo(RunStatus.FAILED);
    assertThat(report.methodMetrics().get(2).errorMessage()).contains("UNIVERSE_VIOLATION");
    assertThat(report.metricDefinitions()).containsKeys("ndcgAt10", "expectedUtilityRegretAt10", "candidateScoringCoverage", "listFillRateAt10");
  }
}
