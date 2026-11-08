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
