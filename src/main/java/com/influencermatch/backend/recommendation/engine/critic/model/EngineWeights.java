package com.influencermatch.backend.recommendation.engine.critic.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Encapsulates the blended weights across the 4 memory dimensions
 * and the calibration mode used (CRITIC_BLENDED vs BUSINESS_FALLBACK).
 * Completely immutable with defensive copying and strict bounds validation.
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

    validateWeightArray(criticWeights, "criticWeights");
    validateWeightArray(businessWeights, "businessWeights");

    // Defensive copy on construction
    criticWeights = (criticWeights != null) ? criticWeights.clone() : null;
    businessWeights = (businessWeights != null) ? businessWeights.clone() : null;
  }

  private static void validateWeight(double w, String name) {
    if (!Double.isFinite(w) || w < 0.0 || w > 1.0) {
      throw new IllegalStateException(name + " must be a finite number in [0.0, 1.0], but got: " + w);
    }
  }

  private static void validateWeightArray(double[] arr, String name) {
    if (arr == null) {
      return;
    }
    if (arr.length != 4) {
      throw new IllegalStateException(name + " must have length 4, but got: " + arr.length);
    }
    double sum = 0.0;
    for (int i = 0; i < 4; i++) {
      double val = arr[i];
      if (!Double.isFinite(val) || val < 0.0 || val > 1.0) {
        throw new IllegalStateException(name + "[" + i + "] must be a finite number in [0.0, 1.0], but got: " + val);
      }
      sum += val;
    }
    if (Math.abs(sum - 1.0) > 1e-4) {
      throw new IllegalStateException(name + " sum must equal 1.0 within 1e-4, but got: " + sum);
    }
  }

  public EngineWeights(double w1, double w2, double w3, double w4, String mode, double lambdaUsed) {
    this(w1, w2, w3, w4, mode, lambdaUsed, new double[] {w1, w2, w3, w4}, new double[] {w1, w2, w3, w4});
  }

  @Override
  public double[] criticWeights() {
    return criticWeights != null ? criticWeights.clone() : null;
  }

  @Override
  public double[] businessWeights() {
    return businessWeights != null ? businessWeights.clone() : null;
  }

  public double[] asArray() {
    return new double[] {w1, w2, w3, w4};
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof EngineWeights other)) return false;
    return Double.compare(other.w1, w1) == 0
        && Double.compare(other.w2, w2) == 0
        && Double.compare(other.w3, w3) == 0
        && Double.compare(other.w4, w4) == 0
        && Double.compare(other.lambdaUsed, lambdaUsed) == 0
        && Objects.equals(mode, other.mode)
        && Arrays.equals(criticWeights, other.criticWeights)
        && Arrays.equals(businessWeights, other.businessWeights);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(w1, w2, w3, w4, mode, lambdaUsed);
    result = 31 * result + Arrays.hashCode(criticWeights);
    result = 31 * result + Arrays.hashCode(businessWeights);
    return result;
  }

  @Override
  public String toString() {
    return String.format(
        "EngineWeights[w=(%.3f, %.3f, %.3f, %.3f), mode=%s, lambda=%.2f]",
        w1, w2, w3, w4, mode, lambdaUsed);
  }
}
