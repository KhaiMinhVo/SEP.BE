package com.influencermatch.backend.recommendation.engine.memory;

import com.influencermatch.backend.brand.model.BrandPattern;
import java.util.Collections;
import java.util.List;

/**
 * Phase 1: SM4 - Brand Context (Brand Affinity Scorer).
 * Evaluates Explicit Tag Preference Coverage: |B_b ∩ T_i| / |B_b|
 * combined with historical brand winning patterns (e.g. platform alignment).
 */
public class BrandAffinityScorer {

  public record Result(
      double sm4Norm,
      double score100,
      int matchedTags,
      int totalTags,
      boolean isConfigured
  ) {}

  public Result score(
      List<String> campaignNiches,
      String candidateNiche,
      String candidateCategory,
      String candidatePlatform,
      List<BrandPattern> brandPatterns) {

    List<String> niches = (campaignNiches != null) ? campaignNiches : Collections.emptyList();
    String candNiche = (candidateNiche != null) ? candidateNiche.trim().toLowerCase() : "";
    String candCat = (candidateCategory != null) ? candidateCategory.trim().toLowerCase() : "";

    boolean isConfigured = !niches.isEmpty();
    double sm4Norm = 0.50; // Cold-start neutral default
    int matches = 0;
    int total = niches.size();

    if (isConfigured) {
      for (String niche : niches) {
        String lowerN = niche.trim().toLowerCase();
        if (lowerN.equalsIgnoreCase(candNiche)
            || lowerN.equalsIgnoreCase(candCat)
            || (!candNiche.isEmpty() && candNiche.contains(lowerN))
            || (!candCat.isEmpty() && candCat.contains(lowerN))) {
          matches++;
        }
      }
      sm4Norm = (double) matches / total;
    }

    // Combine with winning brand patterns and learned feedback patterns if present
    if (brandPatterns != null && !brandPatterns.isEmpty()) {
      for (BrandPattern pattern : brandPatterns) {
        if (pattern == null) continue;

        // 1. Platform alignment boost (+0.10)
        if (pattern.getPlatform() != null
            && candidatePlatform != null
            && pattern.getPlatform().equalsIgnoreCase(candidatePlatform)) {
          sm4Norm = Math.min(1.0, sm4Norm + 0.10);
        }

        // 2. Learned Rejection Feedback (Brand Taste Penalty)
        if ("REJECTION_FEEDBACK".equalsIgnoreCase(pattern.getPatternType())
            && pattern.getPatternValue() != null) {
          com.fasterxml.jackson.databind.JsonNode pVal = pattern.getPatternValue();
          if (pVal.has("NICHE_MISMATCH") && matches == 0 && isConfigured) {
            sm4Norm = Math.max(0.0, sm4Norm - 0.10);
          }
        }
      }
    }

    double score100 = sm4Norm * 100.0;
    return new Result(sm4Norm, score100, matches, total, isConfigured);
  }
}
