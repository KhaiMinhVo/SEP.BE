package com.influencermatch.backend.recommendation.engine.memory;

import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.engine.memory.model.CandidateMemoryScores;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Orchestrates evaluation across all 4 Memory Layers:
 * - SM1: M1 Historical Performance (R/T/K Architecture & Gate)
 * - SM2: Public Performance (ER / VR / STAB Saturation)
 * - SM3: Semantic Relevance (pgvector Cosine Similarity)
 * - SM4: Brand Affinity (Learned Review Hierarchy & Explicit Fallback)
 */
public class FourMemoryScorer {

  private final BayesianHistoryScorer bayesianScorer = new BayesianHistoryScorer();
  private final M1HistoricalScorer m1Scorer = new M1HistoricalScorer();
  private final PublicPerformanceScorer publicScorer = new PublicPerformanceScorer();
  private final SemanticRelevanceScorer semanticScorer = new SemanticRelevanceScorer();
  private final BrandAffinityScorer brandScorer = new BrandAffinityScorer();
  private final LearnedM4Evaluator m4Evaluator = new LearnedM4Evaluator();

  public CandidateMemoryScores evaluate(
      RecommendationCandidateProjection cand,
      NormalizedCandidateContext p0,
      Campaign campaign,
      List<HistoricalCampaign> pastCampaigns,
      List<BrandPattern> brandPatterns) {
    return evaluate(cand, p0, campaign, pastCampaigns, brandPatterns, Collections.emptyList(), LocalDateTime.now());
  }

  public CandidateMemoryScores evaluate(
      RecommendationCandidateProjection cand,
      NormalizedCandidateContext p0,
      Campaign campaign,
      List<HistoricalCampaign> pastCampaigns,
      List<BrandPattern> brandPatterns,
      List<LearnedM4Evaluator.BrandReviewRecord> brandReviews,
      LocalDateTime scoringAsOf) {

    String objective = (campaign != null && campaign.getObjective() != null)
        ? campaign.getObjective().name()
        : "AWARENESS";

    // 1. SM1: M1 Historical Scorer (R / T / K)
    M1HistoricalScorer.M1Result m1Res = m1Scorer.evaluate(pastCampaigns, objective, scoringAsOf);

    // 2. SM2: Public Creator Intelligence
    PublicPerformanceScorer.Result sm2Res = publicScorer.score(p0, cand);

    // 3. SM3: Semantic Relevance
    Double rawCosine = (cand != null) ? cand.getSemanticScore() : null;
    SemanticRelevanceScorer.Result sm3Res = semanticScorer.score(rawCosine);

    // 4. SM4: Brand Affinity (Learned Review Hierarchy & Explicit Fallback)
    List<String> niches = (campaign != null) ? campaign.getNiches() : null;
    String candNiche = (cand != null) ? cand.getNiche() : null;
    String candCat = (cand != null) ? cand.getCategory() : null;
    String candPlatform = (cand != null) ? cand.getPlatform() : null;

    BrandAffinityScorer.Result sm4ExplicitRes =
        brandScorer.score(niches, candNiche, candCat, candPlatform, brandPatterns);

    LearnedM4Evaluator.M4EvaluationResult m4Eval = m4Evaluator.evaluate(
        cand != null ? cand.getCreatorId() : null,
        cand != null ? cand.getMasterId() : null,
        candPlatform,
        candNiche,
        sm4ExplicitRes.sm4Norm(),
        brandReviews
    );

    double finalSm4 = m4Eval.getM4EffectiveScore();
    double finalSm4_100 = finalSm4 * 100.0;

    return new CandidateMemoryScores(
        m1Res.sm1Norm(),
        sm2Res.sm2Norm(),
        sm3Res.sm3Norm(),
        finalSm4,
        m1Res.sm1Norm() * 100.0,
        sm2Res.score100(),
        sm3Res.score100(),
        finalSm4_100,
        sm2Res.erNorm(),
        sm2Res.vrNorm(),
        sm2Res.stabNorm(),
        m1Res.eligibleCollaborations(),
        0,
        !m1Res.isAvailable(),
        sm2Res.postCount(),
        sm2Res.isStabImputed(),
        sm3Res.hasEmbedding(),
        sm4ExplicitRes.isConfigured(),
        sm4ExplicitRes.matchedTags(),
        sm4ExplicitRes.totalTags(),
        m1Res.m1Availability(),
        m1Res.rScore(),
        m1Res.tScore(),
        m1Res.kScore(),
        m1Res.rAvailability(),
        m1Res.tAvailability(),
        m1Res.kAvailability(),
        m1Res.eligibleCollaborations(),
        m1Res.distinctBrandCount(),
        m1Res.maxBrandShare(),
        m4Eval.getM4LearnedScore(),
        m4Eval.getM4ExplicitScore(),
        m4Eval.getM4EffectiveScore(),
        m4Eval.getM4Source()
    );
  }
}
