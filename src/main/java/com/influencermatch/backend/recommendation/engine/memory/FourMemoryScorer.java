package com.influencermatch.backend.recommendation.engine.memory;

import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.engine.memory.model.CandidateMemoryScores;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;
import java.util.List;

/**
 * Orchestrates evaluation across all 4 Memory Layers:
 * - SM1: Bayesian History
 * - SM2: Public Performance
 * - SM3: Semantic Relevance
 * - SM4: Brand Affinity
 */
public class FourMemoryScorer {

  private final BayesianHistoryScorer bayesianScorer = new BayesianHistoryScorer();
  private final PublicPerformanceScorer publicScorer = new PublicPerformanceScorer();
  private final SemanticRelevanceScorer semanticScorer = new SemanticRelevanceScorer();
  private final BrandAffinityScorer brandScorer = new BrandAffinityScorer();

  public CandidateMemoryScores evaluate(
      RecommendationCandidateProjection cand,
      NormalizedCandidateContext p0,
      Campaign campaign,
      List<HistoricalCampaign> pastCampaigns,
      List<BrandPattern> brandPatterns) {

    String objective = (campaign != null && campaign.getObjective() != null)
        ? campaign.getObjective().name()
        : "AWARENESS";

    // 1. SM1: Historical Campaign Performance
    BayesianHistoryScorer.Result sm1Res = bayesianScorer.score(pastCampaigns, objective);

    // 2. SM2: Public Creator Intelligence
    PublicPerformanceScorer.Result sm2Res = publicScorer.score(p0, cand);

    // 3. SM3: Semantic Relevance
    Double rawCosine = (cand != null) ? cand.getSemanticScore() : null;
    SemanticRelevanceScorer.Result sm3Res = semanticScorer.score(rawCosine);

    // 4. SM4: Brand Affinity
    List<String> niches = (campaign != null) ? campaign.getNiches() : null;
    String candNiche = (cand != null) ? cand.getNiche() : null;
    String candCat = (cand != null) ? cand.getCategory() : null;
    String candPlatform = (cand != null) ? cand.getPlatform() : null;

    BrandAffinityScorer.Result sm4Res =
        brandScorer.score(niches, candNiche, candCat, candPlatform, brandPatterns);

    return new CandidateMemoryScores(
        sm1Res.sm1Norm(),
        sm2Res.sm2Norm(),
        sm3Res.sm3Norm(),
        sm4Res.sm4Norm(),
        sm1Res.score100(),
        sm2Res.score100(),
        sm3Res.score100(),
        sm4Res.score100(),
        sm2Res.erNorm(),
        sm2Res.vrNorm(),
        sm2Res.stabNorm(),
        sm1Res.sampleSize(),
        sm1Res.successCount(),
        sm1Res.isColdStart(),
        sm2Res.postCount(),
        sm2Res.isStabImputed(),
        sm3Res.hasEmbedding(),
        sm4Res.isConfigured(),
        sm4Res.matchedTags(),
        sm4Res.totalTags()
    );
  }
}
