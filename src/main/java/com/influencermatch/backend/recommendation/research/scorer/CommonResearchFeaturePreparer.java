package com.influencermatch.backend.recommendation.research.scorer;

import com.influencermatch.backend.recommendation.engine.memory.SemanticRelevanceScorer;
import com.influencermatch.backend.recommendation.engine.phase0.PolicyNormalizer;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedCreatorContextExtension;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord.PostObservation;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * G7C Common M2/M3 Feature Preparation (research-only).
 * Ensures identical M2 (ER*, VR*, STAB*) and M3 (SM3, hasEmbedding) inputs
 * across all primary baselines and EvidenceConditionedShrinkageScorer.
 */
public final class CommonResearchFeaturePreparer {

  public record PreparedM2M3Features(
      boolean isInvalid,
      List<String> invalidReasons,
      String platform,
      Double rawEr,
      Double rawVr,
      Double erNorm,
      Double vrNorm,
      Double stabNorm,
      double sm3Norm,
      boolean hasEmbedding
  ) {}

  private CommonResearchFeaturePreparer() {}

  public static PreparedM2M3Features prepare(
      ObservedEvidenceRecord obs,
      ObservedCreatorContextExtension ext) {

    List<String> invalidReasons = new ArrayList<>();
    checkInvalid(obs.followers(), "followers", invalidReasons);
    checkInvalid(obs.avgViews(), "avgViews", invalidReasons);
    checkInvalid(obs.avgLikes(), "avgLikes", invalidReasons);
    checkInvalid(obs.avgComments(), "avgComments", invalidReasons);
    checkInvalid(obs.avgShares(), "avgShares", invalidReasons);
    checkInvalid(obs.engagementRate(), "engagementRate", invalidReasons);
    if (obs.followers() != null && obs.followers().value() != null && obs.followers().value() < 0) {
      invalidReasons.add("NEGATIVE_FOLLOWERS");
    }
    if (obs.avgViews() != null && obs.avgViews().value() != null && obs.avgViews().value() < 0) {
      invalidReasons.add("NEGATIVE_AVG_VIEWS");
    }
    if (obs.avgLikes() != null && obs.avgLikes().value() != null && obs.avgLikes().value() < 0) {
      invalidReasons.add("NEGATIVE_AVG_LIKES");
    }
    if (obs.engagementRate() != null && obs.engagementRate().value() != null
        && (obs.engagementRate().value() < 0
            || !Double.isFinite(obs.engagementRate().value())
            || obs.engagementRate().value() > ScoringPolicyConfig.ER_EXTREME_OUTLIER_THRESHOLD)) {
      invalidReasons.add("INVALID_ER_RANGE");
    }
    for (PostObservation p : obs.postWindowMetrics()) {
      checkInvalid(p.views(), "post.views", invalidReasons);
      checkInvalid(p.likes(), "post.likes", invalidReasons);
      if ((p.views() != null && p.views().value() != null && p.views().value() < 0)
          || (p.likes() != null && p.likes().value() != null && p.likes().value() < 0)) {
        invalidReasons.add("NEGATIVE_POST_METRIC");
      }
    }

    String platform = obs.platform() != null ? obs.platform().trim().toUpperCase() : "TIKTOK";
    if (!invalidReasons.isEmpty()) {
      return new PreparedM2M3Features(true, List.copyOf(invalidReasons), platform,
          null, null, null, null, null, 0.50, false);
    }

    PolicyNormalizer normalizer = new PolicyNormalizer();
    boolean isFacebook = "FACEBOOK".equals(platform);
    Double followersVal = (obs.followers() != null && obs.followers().value() != null && obs.followers().value() > 0.0)
        ? obs.followers().value() : null;

    List<PostObservation> posts = obs.postWindowMetrics();
    List<PostObservation> postsWithViews = posts.stream()
        .filter(p -> p.views() != null && p.views().value() != null && p.views().value() >= 0.0)
        .toList();

    // 1. Common ER derivation (post-level first, then aggregate engagementRate, then avgLikes / avgViews or followers)
    ErMethod erMethod;
    Double rawEr = null;
    if (!posts.isEmpty()) {
      boolean hasAnyInteraction = posts.stream().anyMatch(CommonResearchFeaturePreparer::hasInteraction);
      if (isFacebook) {
        erMethod = ErMethod.BY_FOLLOWERS;
        if (followersVal != null && hasAnyInteraction) {
          double sumInt = posts.stream().mapToDouble(CommonResearchFeaturePreparer::interactionSum).sum();
          rawEr = (sumInt / posts.size()) / followersVal;
        }
      } else if (!postsWithViews.isEmpty()) {
        erMethod = ErMethod.BY_VIEWS;
        double viewsSum = postsWithViews.stream().mapToDouble(p -> p.views().value()).sum();
        boolean hasViewInteraction = postsWithViews.stream().anyMatch(CommonResearchFeaturePreparer::hasInteraction);
        if (viewsSum > 0.0 && hasViewInteraction) {
          double intSum = postsWithViews.stream().mapToDouble(CommonResearchFeaturePreparer::interactionSum).sum();
          rawEr = intSum / viewsSum;
        }
      } else {
        erMethod = ErMethod.BY_FOLLOWERS;
        if (followersVal != null && hasAnyInteraction) {
          double sumInt = posts.stream().mapToDouble(CommonResearchFeaturePreparer::interactionSum).sum();
          rawEr = (sumInt / posts.size()) / followersVal;
        }
      }
    } else if (obs.engagementRate() != null && obs.engagementRate().value() != null) {
      rawEr = obs.engagementRate().value();
      boolean hasPosAvgViews = obs.avgViews() != null && obs.avgViews().value() != null && obs.avgViews().value() > 0.0;
      erMethod = (!isFacebook && hasPosAvgViews) ? ErMethod.BY_VIEWS : ErMethod.BY_FOLLOWERS;
    } else if (obs.avgLikes() != null && obs.avgLikes().value() != null) {
      boolean hasPosAvgViews = obs.avgViews() != null && obs.avgViews().value() != null && obs.avgViews().value() > 0.0;
      if (!isFacebook && hasPosAvgViews) {
        erMethod = ErMethod.BY_VIEWS;
        rawEr = obs.avgLikes().value() / obs.avgViews().value();
      } else if (followersVal != null) {
        erMethod = ErMethod.BY_FOLLOWERS;
        rawEr = obs.avgLikes().value() / followersVal;
      } else {
        erMethod = isFacebook ? ErMethod.BY_FOLLOWERS : ErMethod.BY_VIEWS;
      }
    } else {
      erMethod = isFacebook ? ErMethod.BY_FOLLOWERS : ErMethod.BY_VIEWS;
    }

    // Fallback: if posts lacked interactions/views but aggregate engagementRate is explicitly observed
    if (rawEr == null && obs.engagementRate() != null && obs.engagementRate().value() != null && !posts.isEmpty() && postsWithViews.isEmpty()) {
      rawEr = obs.engagementRate().value();
    }

    Double erNorm = (rawEr != null && Double.isFinite(rawEr))
        ? normalizer.normalize(rawEr, normalizer.resolveKEr(platform, erMethod))
        : null;

    // 2. Common VR derivation (NOT_APPLICABLE on FACEBOOK; null if followers <= 0 or missing)
    Double rawVr = null;
    if (!isFacebook && followersVal != null) {
      if (!postsWithViews.isEmpty()) {
        double avgV = postsWithViews.stream().mapToDouble(p -> p.views().value()).average().orElse(0.0);
        rawVr = avgV / followersVal;
      } else if (obs.avgViews() != null && obs.avgViews().value() != null) {
        rawVr = obs.avgViews().value() / followersVal;
      }
    }
    Double vrNorm = (rawVr != null && Double.isFinite(rawVr))
        ? normalizer.normalize(rawVr, normalizer.resolveKVr(platform))
        : null;

    // 3. Common STAB derivation (up to 3 most recent posts with views >= 0, >= 2 posts and mean > 0)
    List<Double> stabViews = posts.stream()
        .sorted(Comparator.comparing(PostObservation::postedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(3)
        .filter(p -> p.views() != null && p.views().value() != null && p.views().value() >= 0.0)
        .map(p -> p.views().value())
        .toList();
    Double stabNorm = null;
    if (stabViews.size() >= 2) {
      double mean = stabViews.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
      if (mean > 0.0) {
        double var = 0.0;
        for (double v : stabViews) var += Math.pow(v - mean, 2);
        stabNorm = 1.0 / (1.0 + Math.sqrt(var / (stabViews.size() - 1)) / mean);
      }
    }

    // 4. Common M3 preparation
    Double rawCosine = (ext != null && ext.semanticSimilarity() != null) ? ext.semanticSimilarity().value() : null;
    var sm3Res = new SemanticRelevanceScorer().score(rawCosine);

    return new PreparedM2M3Features(
        false, List.of(), platform,
        rawEr, rawVr, erNorm, vrNorm, stabNorm,
        sm3Res.sm3Norm(), sm3Res.hasEmbedding());
  }

  private static boolean hasInteraction(PostObservation p) {
    return (p.likes() != null && p.likes().value() != null)
        || (p.comments() != null && p.comments().value() != null)
        || (p.shares() != null && p.shares().value() != null);
  }

  private static double interactionSum(PostObservation p) {
    double l = (p.likes() != null && p.likes().value() != null) ? p.likes().value() : 0.0;
    double c = (p.comments() != null && p.comments().value() != null) ? p.comments().value() : 0.0;
    double s = (p.shares() != null && p.shares().value() != null) ? p.shares().value() : 0.0;
    return l + c + s;
  }

  private static void checkInvalid(MetricObservation obs, String field, List<String> reasons) {
    if (obs != null && obs.availability() == MetricAvailability.INVALID) {
      reasons.add("INVALID_" + field.toUpperCase());
    }
  }
}
