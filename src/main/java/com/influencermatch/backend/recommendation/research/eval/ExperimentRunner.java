package com.influencermatch.backend.recommendation.research.eval;

import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.ExperimentRunManifest;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.OracleGroundTruthRecord;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.OfflineScoringResult;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.ScoredCandidateRecord;
import com.influencermatch.backend.recommendation.research.validation.ScorerInputBoundary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ExperimentRunner {

  public interface ResearchScorer {
    String scorerVersion();
    OfflineScoringResult score(ExtendedCampaignSnapshot snapshot, CandidateUniverse universe);
  }

  public enum RunStatus {
    SUCCESS,
    EMPTY_RANKING,
    FAILED
  }

  public record MethodWorldMetrics(
      String scorerVersion,
      RunStatus status,
      String errorMessage,
      double ndcgAt10,
      double expectedUtilityRegretAt10,
      double candidateScoringCoverage,
      double listFillRateAt10,
      int mainCount,
      int provisionalCount,
      int excludedCount,
      List<UUID> rankedTop10WithPadding
  ) {}

  public record PairedWorldEvaluationReport(
      ExperimentRunManifest manifest,
      UUID campaignId,
      List<UUID> universeCreatorIds,
      Map<String, String> metricDefinitions,
      List<MethodWorldMetrics> methodMetrics,
      Map<String, OfflineScoringResult> rawScorerOutputs
  ) {}

  public static final Map<String, String> METRIC_DEFINITIONS = Map.of(
      "metricVersion", EvaluationMetrics.METRIC_VERSION,
      "ndcgAt10", "DCG@10 / IDCG@10 over fixed eligible universe oracle expected_future_utility; 0.0 when IDCG == 0 or empty",
      "expectedUtilityRegretAt10", "(sum(top-d eligible oracle) - sum(top-d ranked oracle)) / d, d=min(10, eligibleUniverseSize); unranked slots contribute 0.0",
      "candidateScoringCoverage", "(mainCount + provisionalCount) / eligibleUniverseCount",
      "listFillRateAt10", "min(10, rankedCount) / 10.0"
  );

  public PairedWorldEvaluationReport evaluateWorld(
      ExperimentRunManifest manifest,
      ExtendedCampaignSnapshot rawSnapshot,
      CandidateUniverse universe,
      List<OracleGroundTruthRecord> oracleRecords,
      List<ResearchScorer> scorers) {

    // Strictly validate snapshot through ScorerInputBoundary before any scorer sees it
    ExtendedCampaignSnapshot safeSnapshot = ScorerInputBoundary.acceptForScorer(rawSnapshot);
    Set<UUID> universeIds = universe.entries().stream()
        .map(CandidateUniverse.CandidateEntry::creatorId)
        .collect(Collectors.toSet());
    Set<UUID> eligibleUniverseIds = universe.entries().stream()
        .filter(e -> e.hardEligibility() == HardEligibilityStatus.ELIGIBLE)
        .map(CandidateUniverse.CandidateEntry::creatorId)
        .collect(Collectors.toSet());
    int eligibleCount = eligibleUniverseIds.size();

    // Oracle is read ONLY here by the evaluator over the fixed eligible universe, never passed to scorers
    Map<UUID, Double> oracleUtilityById = oracleRecords.stream()
        .filter(o -> eligibleUniverseIds.contains(o.creatorId()))
        .collect(Collectors.toMap(
            OracleGroundTruthRecord::creatorId,
            o -> o.syntheticExpectedFutureUtility() != null ? o.syntheticExpectedFutureUtility() : 0.0,
            (a, b) -> a));

    Map<String, OfflineScoringResult> outputs = new LinkedHashMap<>();
    List<MethodWorldMetrics> metrics = new java.util.ArrayList<>();

    for (ResearchScorer scorer : scorers) {
      try {
        OfflineScoringResult res = scorer.score(safeSnapshot, universe);
        List<UUID> rankedMain = res.mainRanked().stream()
            .map(ScoredCandidateRecord::creatorId)
            .toList();
        for (UUID id : rankedMain) {
          if (!universeIds.contains(id)) {
            throw new IllegalStateException("UNIVERSE_VIOLATION: Scorer returned creator outside universe: " + id);
          }
        }
        int scoredTotal = res.mainRanked().size() + res.provisionalRanked().size();
        RunStatus status = rankedMain.isEmpty() ? RunStatus.EMPTY_RANKING : RunStatus.SUCCESS;
        outputs.put(scorer.scorerVersion(), res);
        metrics.add(new MethodWorldMetrics(
            scorer.scorerVersion(),
            status,
            null,
            EvaluationMetrics.ndcgAtK(rankedMain, oracleUtilityById, 10),
            EvaluationMetrics.expectedUtilityRegretAtK(rankedMain, oracleUtilityById, 10),
            EvaluationMetrics.candidateScoringCoverage(scoredTotal, eligibleCount),
            EvaluationMetrics.listFillRateAtK(rankedMain.size(), 10),
            res.mainRanked().size(),
            res.provisionalRanked().size(),
            res.excluded().size(),
            EvaluationMetrics.padShortListToK(rankedMain, 10)));
      } catch (Exception ex) {
        metrics.add(new MethodWorldMetrics(
            scorer.scorerVersion(),
            RunStatus.FAILED,
            ex.getClass().getSimpleName() + ": " + ex.getMessage(),
            0.0,
            EvaluationMetrics.expectedUtilityRegretAtK(List.of(), oracleUtilityById, 10),
            0.0,
            0.0,
            0, 0, 0,
            EvaluationMetrics.padShortListToK(List.of(), 10)));
      }
    }

    return new PairedWorldEvaluationReport(
        manifest,
        safeSnapshot.baseSnapshot().campaignId(),
        universe.entries().stream().map(CandidateUniverse.CandidateEntry::creatorId).toList(),
        METRIC_DEFINITIONS,
        List.copyOf(metrics),
        outputs);
  }
}
