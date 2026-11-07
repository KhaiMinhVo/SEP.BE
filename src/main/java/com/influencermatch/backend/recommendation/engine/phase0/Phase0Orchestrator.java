package com.influencermatch.backend.recommendation.engine.phase0;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.phase0.enums.Phase0Status;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Phase0Orchestrator {

  private final EligibilityChecker eligibilityChecker = new EligibilityChecker();
  private final EvidenceValidator evidenceValidator = new EvidenceValidator();
  private final PolicyNormalizer policyNormalizer = new PolicyNormalizer();

  public List<NormalizedCandidateContext> execute(
      List<RecommendationCandidateProjection> candidates,
      Campaign campaign,
      Set<UUID> excludedIds,
      Map<UUID, List<Post>> postsByCreator) {

    List<NormalizedCandidateContext> results = new ArrayList<>();

    if (candidates == null || candidates.isEmpty()) {
      return results;
    }

    for (RecommendationCandidateProjection cand : candidates) {
      UUID creatorId = cand.getCreatorId();
      String platform = cand.getPlatform() != null ? cand.getPlatform().trim().toUpperCase() : "";

      // LUỒNG 1: Eligibility (Hard Constraints with Precedence INELIGIBLE > PENDING_DATA > ELIGIBLE)
      var elig = eligibilityChecker.check(cand, campaign, excludedIds);
      if (elig.isExcluded()) {
        results.add(new NormalizedCandidateContext(
            creatorId, cand.getMasterId(), platform, Phase0Status.INELIGIBLE,
            elig.reasons(), 0.0, 0.0, Double.NaN, 0.0, 0.0, Double.NaN, null, 0, false, 0.0,
            ScoringPolicyConfig.CALIBRATION_VERSION
        ));
        continue;
      }

      if (elig.isPendingData()) {
        results.add(new NormalizedCandidateContext(
            creatorId, cand.getMasterId(), platform, Phase0Status.PENDING_DATA,
            elig.reasons(), 0.0, 0.0, Double.NaN, 0.0, 0.0, Double.NaN, null, 0, false, 0.0,
            ScoringPolicyConfig.CALIBRATION_VERSION
        ));
        continue;
      }

      // LUỒNG 2 & 3: Evidence Validation & Policy-Based Saturation Normalization with Error Isolation
      try {
        // LUỒNG 2: Evidence Validation (Crawler 3 bài gần nhất)
        List<Post> posts = postsByCreator != null ? postsByCreator.get(creatorId) : null;
        var ev = evidenceValidator.evaluate(cand, posts);

        Phase0Status status = ev.canComputeStability() ? Phase0Status.ELIGIBLE : Phase0Status.PENDING_DATA;

        // LUỒNG 3: Policy-Based Saturation Normalization (x / (x + K))
        double kEr = policyNormalizer.resolveKEr(platform, ev.erMethod());
        double kVr = policyNormalizer.resolveKVr(platform);

        double erNorm = policyNormalizer.normalize(ev.rawEr(), kEr);
        double vrNorm = policyNormalizer.normalize(ev.rawVr(), kVr);
        double stabNorm = Double.isNaN(ev.rawStab()) ? Double.NaN : Math.max(0.0, Math.min(1.0, ev.rawStab()));

        results.add(new NormalizedCandidateContext(
            creatorId, cand.getMasterId(), platform, status,
            List.of(), erNorm, vrNorm, stabNorm,
            ev.rawEr(), ev.rawVr(), ev.rawStab(),
            ev.erMethod(), ev.postCount(), ev.isStabImputed(), ev.evidenceReliabilityM2(),
            ScoringPolicyConfig.CALIBRATION_VERSION
        ));
      } catch (IllegalArgumentException ex) {
        // Error isolation: Creator-level data error (e.g. negative numbers)
        // Mark individual creator INELIGIBLE without crashing the entire run
        results.add(new NormalizedCandidateContext(
            creatorId, cand.getMasterId(), platform, Phase0Status.INELIGIBLE,
            List.of("INVALID_METRICS: " + ex.getMessage()), 0.0, 0.0, Double.NaN,
            0.0, 0.0, Double.NaN, null, 0, false, 0.0,
            ScoringPolicyConfig.CALIBRATION_VERSION
        ));
      }
      // Note: IllegalStateException (e.g. invalid system policy K <= 0) is intentionally unhandled
      // so it halts the entire run if system policy configuration is broken.
    }

    return results;
  }
}
