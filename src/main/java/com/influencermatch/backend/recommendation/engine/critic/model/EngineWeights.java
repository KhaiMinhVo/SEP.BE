package com.influencermatch.backend.recommendation.engine.critic.model;

import java.util.Arrays;

/**
 * Encapsulates the blended weights across the 4 memory dimensions
 * and the calibration mode used (CRITIC_BLENDED vs BUSINESS_FALLBACK).
 */
public record EngineWeights(
    double w1, // Historical Performance (SM1)
    double w2, // Public Intelligence (SM2)
    double w3, // Semantic Relevance (SM3)
    double w4, // Brand Affinity (SM4)
    String mode, // CRITIC_BLENDED or BUSINESS_FALLBACK
    double lambdaUsed,
    double[] criticWeights, // Raw q_j statistical weights
    double[] businessWeights // Raw b_g,j business weights
) {
  public EngineWeights {
    validateWeight(w1, "w1");
    validateWeight(w2, "w2");
    validateWeight(w3, "w3");
    validateWeight(w4, "w4");
    double sum = w1 + w2 + w3 + w4;
    if (Math.abs(sum - 1.0) > 1e-4) {
      throw new IllegalStateException("INVALID_WEIGHT_SUM: Weights must sum to 1.0, but got: " + sum);
    }
  }

  private static void validateWeight(double w, String name) {
    if (!Double.isFinite(w) || w < 0.0 || w > 1.0) {
      throw new IllegalStateException(name + " must be a finite number in [0.0, 1.0], but got: " + w);
    }
  }

  public EngineWeights(double w1, double w2, double w3, double w4, String mode, double lambdaUsed) {
    this(w1, w2, w3, w4, mode, lambdaUsed, new double[] {w1, w2, w3, w4}, new double[] {w1, w2, w3, w4});
  }

  public double[] asArray() {
    return new double[] {w1, w2, w3, w4};
  }

  @Override
  public String toString() {
    return String.format(
        "EngineWeights[w=(%.3f, %.3f, %.3f, %.3f), mode=%s, lambda=%.2f]",
        w1, w2, w3, w4, mode, lambdaUsed);
  }
}
