package com.influencermatch.backend.recommendation.engine.critic;

import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import java.util.Optional;

/**
 * Phase 3: Dynamic Weight Blender.
 * Blends business priority weights b_g,j (based on Campaign Objective)
 * with statistical objective weights q_j from CRITIC using parameter lambda:
 * w_j = (1 - lambda) * b_g,j + lambda * q_j.
 * Automatically engages BUSINESS_FALLBACK (lambda = 0) if statistical variance is absent.
 */
public class DynamicWeightBlender {

  public static final double DEFAULT_LAMBDA = 0.30;

  public EngineWeights blend(String objective, Optional<double[]> criticWeightsOpt) {
    return blend(objective, criticWeightsOpt, DEFAULT_LAMBDA);
  }

  public EngineWeights blend(String objective, Optional<double[]> criticWeightsOpt, double lambda) {
    if (!Double.isFinite(lambda) || lambda < 0.0 || lambda > 1.0) {
      throw new IllegalStateException(
          "INVALID_BLEND_LAMBDA: lambda must be a finite number in [0.0, 1.0], but got: " + lambda);
    }

    double[] b = resolveBusinessWeights(objective);

    if (criticWeightsOpt.isEmpty() || lambda == 0.0) {
      return new EngineWeights(
          b[0], b[1], b[2], b[3],
          "BUSINESS_FALLBACK",
          0.0,
          null,
          b
      );
    }

    double[] q = criticWeightsOpt.get();
    double effectiveLambda = lambda;

    double[] w = new double[4];
    double sum = 0.0;
    for (int j = 0; j < 4; j++) {
      w[j] = (1.0 - effectiveLambda) * b[j] + effectiveLambda * q[j];
      sum += w[j];
    }

    // Ensure unit sum
    for (int j = 0; j < 4; j++) {
      w[j] /= sum;
      if (!Double.isFinite(w[j]) || w[j] < 0.0) {
        throw new IllegalStateException("INVALID_ENGINE_WEIGHT: Computed weight at index " + j + " is invalid: " + w[j]);
      }
    }

    return new EngineWeights(
        w[0], w[1], w[2], w[3],
        "CRITIC_BLENDED",
        effectiveLambda,
        q,
        b
    );
  }

  public double[] resolveBusinessWeights(String objective) {
    if (objective == null) {
      return new double[] {0.15, 0.35, 0.35, 0.15};
    }
    String normalized = objective.trim().toUpperCase();
    return switch (normalized) {
      case "AWARENESS", "BRAND_AWARENESS" -> new double[] {0.10, 0.35, 0.40, 0.15};
      case "ENGAGEMENT" -> new double[] {0.15, 0.35, 0.35, 0.15};
      case "TRAFFIC" -> new double[] {0.20, 0.35, 0.30, 0.15};
      case "CONVERSION", "CONVERSION_SALES", "SALES" -> new double[] {0.40, 0.15, 0.30, 0.15};
      case "COMMUNITY_GROWTH" -> new double[] {0.20, 0.30, 0.30, 0.20};
      default -> new double[] {0.15, 0.35, 0.35, 0.15};
    };
  }
}
