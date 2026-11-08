package com.influencermatch.backend.recommendation.engine;

import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.dto.ScoreBreakdownDto;
import com.influencermatch.backend.recommendation.engine.confidence.ConfidenceCalculator;
import com.influencermatch.backend.recommendation.engine.confidence.model.ConfidenceAssessment;
import com.influencermatch.backend.recommendation.engine.critic.CriticWeightCalculator;
import com.influencermatch.backend.recommendation.engine.critic.DynamicWeightBlender;
import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.engine.memory.FourMemoryScorer;
import com.influencermatch.backend.recommendation.engine.memory.model.CandidateMemoryScores;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;
import com.influencermatch.backend.recommendation.engine.presentation.DisplaySorter;
import com.influencermatch.backend.recommendation.engine.presentation.enums.PriceStatus;
import com.influencermatch.backend.recommendation.engine.topsis.TopsisRankingEngine;
import com.influencermatch.backend.recommendation.engine.topsis.model.TopsisResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * End-to-end engine orchestrating:
 * - Phase 1: Four-Memory Scoring (SM1, SM2, SM3, SM4)
 * - Phase 2: CRITIC Statistical Weighting
 * - Phase 3: Dynamic Weight Blending
 * - Phase 4: Fixed-Ideal TOPSIS Ranking
 * - Phase 5: Recommendation Confidence Assessment
 */
@Slf4j
public class FourMemoryTopsisEngine {

  private final FourMemoryScorer fourMemoryScorer = new FourMemoryScorer();
  private final CriticWeightCalculator criticCalculator = new CriticWeightCalculator();
  private final DynamicWeightBlender weightBlender = new DynamicWeightBlender();
  private final TopsisRankingEngine topsisEngine = new TopsisRankingEngine();
  private final ConfidenceCalculator confidenceCalculator = new ConfidenceCalculator();

  public record EvaluatedCandidate(
      RecommendationCandidateProjection candidate,
      CandidateMemoryScores memoryScores,
      TopsisResult topsisResult,
      ConfidenceAssessment confidence,
      EngineWeights weights
  ) implements DisplaySorter.DisplayableItem, TopsisRankingEngine.RankableCandidate {

    @Override
    public UUID creatorId() {
      return candidate.getCreatorId();
    }

    @Override
    public double matchScore() {
      return topsisResult.matchScore();
    }

    @Override
    public double sm3() {
      return memoryScores.sm3();
    }

    @Override
    public double sm2() {
      return memoryScores.sm2();
    }

    @Override
    public PriceStatus priceStatus() {
      return PriceStatus.PRICE_UNKNOWN;
    }
  }

  /**
   * Evaluates and ranks a pool of candidates using Four-Memory CRITIC-TOPSIS.
   */
  public List<EvaluatedCandidate> evaluateAndRank(
      List<RecommendationCandidateProjection> candidates,
      Map<UUID, NormalizedCandidateContext> phase0Map,
      Campaign campaign,
      Function<RecommendationCandidateProjection, List<HistoricalCampaign>> historyProvider,
      List<BrandPattern> brandPatterns) {

    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }

    String objective = (campaign != null && campaign.getObjective() != null)
        ? campaign.getObjective().name()
        : "AWARENESS";

    // 1. Phase 1: Evaluate 4 Memories for each candidate with Fault Isolation
    List<IntermediateEvaluation> intermediateList = new ArrayList<>(candidates.size());
    List<double[]> matrix = new ArrayList<>(candidates.size());

    for (RecommendationCandidateProjection cand : candidates) {
      if (cand == null) continue;
      try {
        NormalizedCandidateContext p0 = (phase0Map != null) ? phase0Map.get(cand.getCreatorId()) : null;
        List<HistoricalCampaign> past = (historyProvider != null) ? historyProvider.apply(cand) : Collections.emptyList();

        CandidateMemoryScores scores =
            fourMemoryScorer.evaluate(cand, p0, campaign, past, brandPatterns);

        intermediateList.add(new IntermediateEvaluation(cand, scores));
        matrix.add(scores.asVector());
      } catch (IllegalArgumentException ex) {
        // Cách ly lỗi cấp ứng viên (Fault Isolation):
        // Không để một ứng viên có dữ liệu lỗi/NaN làm sập toàn bộ Recommendation Run của các ứng viên khác
        log.warn("Candidate {} isolated during Phase 1 scoring due to data validation error: {}",
            cand.getCreatorId(), ex.getMessage());
      }
    }

    // 2. Phase 2 & 3: CRITIC Weighting & Dynamic Weight Blending
    Optional<double[]> criticWeightsOpt = criticCalculator.calculate(matrix);
    EngineWeights weights = weightBlender.blend(objective, criticWeightsOpt);

    // 3. Phase 4 & 5: Fixed-Ideal TOPSIS & Confidence Assessment
    List<EvaluatedCandidate> evaluatedList = new ArrayList<>(intermediateList.size());
    for (IntermediateEvaluation inter : intermediateList) {
      TopsisResult topsis = topsisEngine.compute(inter.scores, weights);
      ConfidenceAssessment confidence = confidenceCalculator.calculate(inter.scores, weights);

      evaluatedList.add(new EvaluatedCandidate(
          inter.cand,
          inter.scores,
          topsis,
          confidence,
          weights
      ));
    }

    return evaluatedList;
  }

  /**
   * Evaluates a single candidate on-demand (e.g. for standalone score calculation).
   */
  public EvaluatedCandidate evaluateSingle(
      RecommendationCandidateProjection cand,
      NormalizedCandidateContext p0,
      Campaign campaign,
      List<HistoricalCampaign> past,
      List<BrandPattern> brandPatterns) {

    String objective = (campaign != null && campaign.getObjective() != null)
        ? campaign.getObjective().name()
        : "AWARENESS";

    EngineWeights weights = weightBlender.blend(objective, Optional.empty()); // Uses objective-specific business weights
    return evaluateSingle(cand, p0, campaign, past, brandPatterns, weights);
  }

  /**
   * Evaluates a single candidate using an explicitly provided weight vector (e.g. sharing the same run weights).
   */
  public EvaluatedCandidate evaluateSingle(
      RecommendationCandidateProjection cand,
      NormalizedCandidateContext p0,
      Campaign campaign,
      List<HistoricalCampaign> past,
      List<BrandPattern> brandPatterns,
      EngineWeights weights) {

    CandidateMemoryScores scores = fourMemoryScorer.evaluate(cand, p0, campaign, past, brandPatterns);
    TopsisResult topsis = topsisEngine.compute(scores, weights);
    ConfidenceAssessment confidence = confidenceCalculator.calculate(scores, weights);

    return new EvaluatedCandidate(cand, scores, topsis, confidence, weights);
  }

  public EngineWeights getFallbackWeights(String objective) {
    return weightBlender.blend(objective, Optional.empty());
  }

  /**
   * Helper to convert an EvaluatedCandidate to the backwards-compatible ScoreBreakdownDto.
   */
  public ScoreBreakdownDto toScoreBreakdownDto(EvaluatedCandidate evaluated) {
    CandidateMemoryScores m = evaluated.memoryScores();
    return ScoreBreakdownDto.builder()
        .m1Score(roundTwoDecimals(m.m1Score100()))
        .m2Score(roundTwoDecimals(m.m2Score100()))
        .m3Score(roundTwoDecimals(m.m3Score100()))
        .m4Score(roundTwoDecimals(m.m4Score100()))
        .followerScore(roundTwoDecimals(m.vrNorm() * 100.0))
        .engagementScore(roundTwoDecimals(m.erNorm() * 100.0))
        .semanticMatchScore(roundTwoDecimals(m.m3Score100()))
        .nicheMatchScore(roundTwoDecimals(m.m4Score100()))
        .finalScore(roundTwoDecimals(evaluated.topsisResult().matchScore()))
        .build();
  }

  private double roundTwoDecimals(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalStateException("TOPSIS_SCORE_CORRUPTED: Value cannot be NaN or Infinite: " + value);
    }
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  private record IntermediateEvaluation(
      RecommendationCandidateProjection cand,
      CandidateMemoryScores scores
  ) {}
}
