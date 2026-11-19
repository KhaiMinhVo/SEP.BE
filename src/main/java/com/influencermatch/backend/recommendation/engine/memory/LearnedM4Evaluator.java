package com.influencermatch.backend.recommendation.engine.memory;

import java.util.*;
import lombok.Builder;
import lombok.Data;

/**
 * Phase 3: Learned M4 Evaluator with 4-Tier Evidence Hierarchy.
 *
 * 1. DIRECT_BRAND_CREATOR: >= 3 Reviews between Brand and Creator/Master.
 * 2. COHORT_PLATFORM_NICHE: >= 5 Reviews from >= 3 distinct Masters on (Platform, Niche).
 * 3. COHORT_PLATFORM_ONLY: >= 5 Reviews from >= 3 distinct Masters on Platform.
 * 4. INSUFFICIENT_EVIDENCE: Fallback to Explicit Brand Affinity Scorer.
 *
 * Never adds fixed +0.10 bonuses into database counters.
 */
public class LearnedM4Evaluator {

  public record BrandReviewRecord(
      UUID reviewId,
      UUID brandProfileId,
      UUID creatorId,
      UUID masterId,
      String platform,
      String niche,
      int brandFitRating // 1 to 5
  ) {}

  @Data
  @Builder
  public static class M4EvaluationResult {
    private Double m4LearnedScore;   // [0, 1] or null if fallback
    private double m4ExplicitScore;  // [0, 1] from BrandAffinityScorer
    private double m4EffectiveScore; // [0, 1] final score used for TOPSIS
    private String m4Source;         // DIRECT_BRAND_CREATOR | COHORT_PLATFORM_NICHE | COHORT_PLATFORM_ONLY | EXPLICIT_FALLBACK
    private int directReviewCount;
    private int cohortReviewCount;
    private int cohortMasterCount;
  }

  public M4EvaluationResult evaluate(
      UUID creatorId,
      UUID masterId,
      String platform,
      String niche,
      double explicitScore,
      List<BrandReviewRecord> brandReviews) {

    if (brandReviews == null || brandReviews.isEmpty()) {
      return M4EvaluationResult.builder()
          .m4LearnedScore(null)
          .m4ExplicitScore(explicitScore)
          .m4EffectiveScore(explicitScore)
          .m4Source("EXPLICIT_FALLBACK")
          .directReviewCount(0)
          .cohortReviewCount(0)
          .cohortMasterCount(0)
          .build();
    }

    String normPlatform = (platform != null) ? platform.trim().toUpperCase() : "";
    String normNiche = (niche != null) ? niche.trim().toLowerCase() : "";

    // TIER 1: DIRECT_BRAND_CREATOR (>= 3 Reviews directly with this Creator or Master)
    List<BrandReviewRecord> directReviews = brandReviews.stream()
        .filter(r -> (creatorId != null && creatorId.equals(r.creatorId()))
            || (masterId != null && masterId.equals(r.masterId())))
        .toList();

    if (directReviews.size() >= 3) {
      double avgDirect = directReviews.stream()
          .mapToInt(BrandReviewRecord::brandFitRating)
          .average()
          .orElse(3.0);

      double normScore = Math.max(0.0, Math.min(1.0, (avgDirect - 1.0) / 4.0));
      return M4EvaluationResult.builder()
          .m4LearnedScore(normScore)
          .m4ExplicitScore(explicitScore)
          .m4EffectiveScore(normScore)
          .m4Source("DIRECT_BRAND_CREATOR")
          .directReviewCount(directReviews.size())
          .cohortReviewCount(0)
          .cohortMasterCount(0)
          .build();
    }

    // TIER 2: COHORT_PLATFORM_NICHE (>= 5 Reviews from >= 3 distinct Masters)
    if (!normPlatform.isEmpty() && !normNiche.isEmpty()) {
      List<BrandReviewRecord> nicheCohort = brandReviews.stream()
          .filter(r -> normPlatform.equalsIgnoreCase(r.platform())
              && normNiche.equalsIgnoreCase(r.niche() != null ? r.niche().trim().toLowerCase() : ""))
          .toList();

      Set<UUID> distinctMasters = new HashSet<>();
      for (BrandReviewRecord r : nicheCohort) {
        distinctMasters.add(r.masterId() != null ? r.masterId() : r.creatorId());
      }

      if (nicheCohort.size() >= 5 && distinctMasters.size() >= 3) {
        double avgCohort = nicheCohort.stream()
            .mapToInt(BrandReviewRecord::brandFitRating)
            .average()
            .orElse(3.0);

        double normScore = Math.max(0.0, Math.min(1.0, (avgCohort - 1.0) / 4.0));
        return M4EvaluationResult.builder()
            .m4LearnedScore(normScore)
            .m4ExplicitScore(explicitScore)
            .m4EffectiveScore(normScore)
            .m4Source("COHORT_PLATFORM_NICHE")
            .directReviewCount(directReviews.size())
            .cohortReviewCount(nicheCohort.size())
            .cohortMasterCount(distinctMasters.size())
            .build();
      }
    }

    // TIER 3: COHORT_PLATFORM_ONLY (>= 5 Reviews from >= 3 distinct Masters on Platform)
    if (!normPlatform.isEmpty()) {
      List<BrandReviewRecord> platformCohort = brandReviews.stream()
          .filter(r -> normPlatform.equalsIgnoreCase(r.platform()))
          .toList();

      Set<UUID> distinctMasters = new HashSet<>();
      for (BrandReviewRecord r : platformCohort) {
        distinctMasters.add(r.masterId() != null ? r.masterId() : r.creatorId());
      }

      if (platformCohort.size() >= 5 && distinctMasters.size() >= 3) {
        double avgCohort = platformCohort.stream()
            .mapToInt(BrandReviewRecord::brandFitRating)
            .average()
            .orElse(3.0);

        double normScore = Math.max(0.0, Math.min(1.0, (avgCohort - 1.0) / 4.0));
        return M4EvaluationResult.builder()
            .m4LearnedScore(normScore)
            .m4ExplicitScore(explicitScore)
            .m4EffectiveScore(normScore)
            .m4Source("COHORT_PLATFORM_ONLY")
            .directReviewCount(directReviews.size())
            .cohortReviewCount(platformCohort.size())
            .cohortMasterCount(distinctMasters.size())
            .build();
      }
    }

    // TIER 4: INSUFFICIENT_EVIDENCE -> Explicit Fallback
    return M4EvaluationResult.builder()
        .m4LearnedScore(null)
        .m4ExplicitScore(explicitScore)
        .m4EffectiveScore(explicitScore)
        .m4Source("EXPLICIT_FALLBACK")
        .directReviewCount(directReviews.size())
        .cohortReviewCount(0)
        .cohortMasterCount(0)
        .build();
  }
}
