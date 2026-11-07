package com.influencermatch.backend.recommendation.engine.memory;

import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.engine.phase0.PolicyNormalizer;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;

/**
 * Phase 1: SM2 - Public Creator Intelligence Scorer.
 * Chốt Missing-Feature Policy: Mọi Creator trong bảng xếp hạng chính được đánh giá
 * trên CÙNG MỘT ĐỊNH NGHĨA đồng nhất:
 * SM2 = 1/3 ER* + 1/3 VIEW* + 1/3 STAB* (mẫu số luôn luôn là 3.0).
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
      // Fallback khi p0 chưa được tính trước (ví dụ standalone direct call)
      double rawEr = 0.0;
      if (cand != null && cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0 && cand.getAvgLikes() != null) {
        rawEr = cand.getAvgLikes().doubleValue() / cand.getAvgViews().doubleValue();
      } else if (cand != null && cand.getEngagementRate() != null) {
        double v = cand.getEngagementRate().doubleValue();
        if (v < 0.0) {
          throw new IllegalArgumentException("NEGATIVE_METRIC: engagementRate cannot be negative (" + v + ")");
        }
        if (v > ScoringPolicyConfig.DATA_CONTRACT_ER_MAX) {
          throw new IllegalArgumentException(
              String.format("INVALID_ER_SCALE: engagementRate exceeds 1.0 (%f). System contract requires decimal ratio in [0, 1].", v));
        }
        rawEr = v;
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

      stabNorm = Double.NaN;
      isStabImputed = true;
    }

    // MISSING-FEATURE POLICY CHO SM2:
    // Đảm bảo mọi Creator trong bảng xếp hạng chính được đánh giá trên cùng một định nghĩa:
    // SM2 = 1/3 ER* + 1/3 VIEW* + 1/3 STAB* (mẫu số luôn luôn là 3.0).
    // Khi thiếu STAB: Áp dụng sàn chính sách bảo thủ (STAB_MISSING_POLICY_FLOOR = 0.0).
    double effectiveStab;
    if (Double.isNaN(stabNorm)) {
      effectiveStab = ScoringPolicyConfig.STAB_MISSING_POLICY_FLOOR; // 0.0
      isStabImputed = true;
    } else {
      effectiveStab = stabNorm;
    }

    double sm2Norm = (erNorm + vrNorm + effectiveStab) / 3.0;
    double score100 = sm2Norm * 100.0;

    return new Result(sm2Norm, score100, erNorm, vrNorm, stabNorm, postCount, isStabImputed);
  }
}
