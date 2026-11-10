package com.influencermatch.backend.recommendation.dto;

import com.influencermatch.backend.recommendation.engine.critic.CriticWeightCalculator;
import com.influencermatch.backend.recommendation.engine.critic.DynamicWeightBlender;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;
import com.influencermatch.backend.recommendation.engine.presentation.MmrReranker;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DM-04: Read-Only metadata response for Four-Memory Algorithm Configuration Inspector.
 * Single source of truth directly derived from frozen engine configurations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationConfigResponseDto {

  private String engineVersion;
  private String calibrationVersion;
  private boolean dynamicallyMutable;
  private String flowName;

  private HalfSaturationConfig halfSaturation;
  private StabilityConfig stability;
  private OutlierSanityConfig outlierSanity;
  private DynamicWeightingConfig dynamicWeighting;
  private DiversityRerankingConfig diversityReranking;
  private List<String> futureScope;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class HalfSaturationConfig {
    private Map<String, Double> erViewsK;
    private Map<String, Double> erFollowersK;
    private Map<String, Double> vrK;
    private double defaultFallbackK;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class StabilityConfig {
    private double stabMissingPolicyFloor;
    private String description;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OutlierSanityConfig {
    private double erExtremeOutlierThreshold;
    private String description;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DynamicWeightingConfig {
    private double defaultLambda;
    private int minPoolSizeForCritic;
    private String blendFormula;
    private String fallbackMode;
    private String interpretationPolicy;
    private Map<String, double[]> canonicalObjectiveWeights;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DiversityRerankingConfig {
    private double defaultMmrLambda;
    private String mmrFormula;
    private String intraBucketMmrStatus;
  }

  public static RecommendationConfigResponseDto createCurrent() {
    DynamicWeightBlender blender = new DynamicWeightBlender();
    Map<String, double[]> objectiveWeights =
        Map.of(
            "AWARENESS", blender.resolveBusinessWeights("AWARENESS"),
            "ENGAGEMENT", blender.resolveBusinessWeights("ENGAGEMENT"),
            "TRAFFIC", blender.resolveBusinessWeights("TRAFFIC"),
            "CONVERSION", blender.resolveBusinessWeights("CONVERSION"));

    return RecommendationConfigResponseDto.builder()
        .engineVersion("Four-Memory TOPSIS Engine v1.0")
        .calibrationVersion(ScoringPolicyConfig.CALIBRATION_VERSION)
        .dynamicallyMutable(false)
        .flowName("DM-04: Tra cứu Cấu hình Thuật toán Four-Memory (View Recommendation Configuration)")
        .halfSaturation(
            HalfSaturationConfig.builder()
                .erViewsK(
                    Map.of(
                        "TIKTOK", ScoringPolicyConfig.K_ER_VIEWS_TIKTOK,
                        "INSTAGRAM", ScoringPolicyConfig.K_ER_VIEWS_INSTAGRAM,
                        "FACEBOOK", ScoringPolicyConfig.K_ER_VIEWS_FACEBOOK))
                .erFollowersK(
                    Map.of(
                        "INSTAGRAM", ScoringPolicyConfig.K_ER_FOLLOWERS_INSTAGRAM,
                        "FACEBOOK", ScoringPolicyConfig.K_ER_FOLLOWERS_FACEBOOK))
                .vrK(
                    Map.of(
                        "TIKTOK", ScoringPolicyConfig.K_VR_TIKTOK,
                        "INSTAGRAM", ScoringPolicyConfig.K_VR_INSTAGRAM,
                        "FACEBOOK", ScoringPolicyConfig.K_VR_FACEBOOK))
                .defaultFallbackK(ScoringPolicyConfig.DEFAULT_FALLBACK_K)
                .build())
        .stability(
            StabilityConfig.builder()
                .stabMissingPolicyFloor(ScoringPolicyConfig.STAB_MISSING_POLICY_FLOOR)
                .description("Conservative floor = 0.0 applied to STAB when candidate has < 2 posts.")
                .build())
        .outlierSanity(
            OutlierSanityConfig.builder()
                .erExtremeOutlierThreshold(ScoringPolicyConfig.ER_EXTREME_OUTLIER_THRESHOLD)
                .description("Extreme Outlier Sanity Threshold (10.0 / 1000%): Rule-based sanity guardrail to flag anomalous or corrupted engagement rates; not a deterministic proof of viral authenticity.")
                .build())
        .dynamicWeighting(
            DynamicWeightingConfig.builder()
                .defaultLambda(DynamicWeightBlender.DEFAULT_LAMBDA)
                .minPoolSizeForCritic(CriticWeightCalculator.MIN_POOL_SIZE)
                .blendFormula("w_j = (1 - lambda) * b_g,j + lambda * q_j")
                .fallbackMode("BUSINESS_FALLBACK (lambda = 0) engaged when: pool size < 10, criteria variance < EPSILON, active criteria < 2, total information C == 0, or lambda == 0")
                .interpretationPolicy("Business Objective Weights represent predefined decision priorities across the four evidence dimensions [M1, M2, M3, M4]. They are not direct predictions of campaign-specific traffic, engagement, or conversion outcomes.")
                .canonicalObjectiveWeights(objectiveWeights)
                .build())
        .diversityReranking(
            DiversityRerankingConfig.builder()
                .defaultMmrLambda(MmrReranker.DEFAULT_LAMBDA)
                .mmrFormula("MMR(i) = lambda * rel(i) - (1 - lambda) * max_{s in S} sim(i, s)")
                .intraBucketMmrStatus(MmrReranker.INTRA_BUCKET_MMR_STATUS)
                .build())
        .futureScope(
            List.of(
                "DYNAMIC_WEIGHT_OVERRIDE",
                "SIMULATION_SANDBOX",
                "OBJECTIVE_PRESET_CREATOR",
                "REALTIME_ONLINE_EXPERIMENTATION"))
        .build();
  }
}
