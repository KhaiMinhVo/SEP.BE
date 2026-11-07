package com.influencermatch.backend.recommendation.engine.memory;

import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.engine.phase0.PolicyNormalizer;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;

/**
 * Phase 1: SM2 - Public Creator Intelligence Scorer.
 * Uses Equal-Weight Mean of 3 normalized public metrics:
 * SM2 = 1/3 ER* + 1/3 VIEW* + 1/3 STAB.
 */
public class PublicPerformanceScorer {

  private final PolicyNormalizer normalizer = new PolicyNormalizer();

  public record Result(
      double sm2Norm,
      double score100,
      double erNorm,
      double vrNorm,
      double stabNorm,
      int postCount,
      boolean isStabImputed
  ) {}

  public Result score(NormalizedCandidateContext p0, RecommendationCandidateProjection cand) {
    double erNorm;
    double vrNorm;
    double stabNorm;
    int postCount = 0;
    boolean isStabImputed = false;

    if (p0 != null) {
      erNorm = p0.erNormalized();
      vrNorm = p0.vrNormalized();
      stabNorm = p0.stabNormalized();
      postCount = p0.postCount();
      isStabImputed = p0.isStabImputed();
    } else {
      // Fallback if Phase 0 context was not pre-computed
      double rawEr = 0.0;
      if (cand != null && cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0 && cand.getAvgLikes() != null) {
        rawEr = cand.getAvgLikes().doubleValue() / cand.getAvgViews().doubleValue();
      } else if (cand != null && cand.getEngagementRate() != null) {
        rawEr = cand.getEngagementRate().doubleValue() / 100.0;
      }
      String platform = (cand != null) ? cand.getPlatform() : "TIKTOK";
      double kEr = normalizer.resolveKEr(platform, ErMethod.BY_VIEWS);
      erNorm = normalizer.normalize(rawEr, kEr);

      double rawVr = 0.0;
      long followers = (cand != null && cand.getFollowers() != null) ? cand.getFollowers() : 0L;
      double avgViews = (cand != null && cand.getAvgViews() != null) ? cand.getAvgViews().doubleValue() : 0.0;
      if (followers > 0 && avgViews > 0) {
        rawVr = avgViews / followers;
      }
      double kVr = normalizer.resolveKVr(platform);
      vrNorm = normalizer.normalize(rawVr, kVr);

      stabNorm = (avgViews > 0) ? 0.75 : 0.50;
      isStabImputed = true;
    }

    double sm2Norm = (erNorm + vrNorm + stabNorm) / 3.0;
    double score100 = sm2Norm * 100.0;

    return new Result(sm2Norm, score100, erNorm, vrNorm, stabNorm, postCount, isStabImputed);
  }
}
