package com.influencermatch.backend.recommendation.engine.critic;

import java.util.List;
import java.util.Optional;

/**
 * Phase 2: CRITIC (Criteria Importance Through Intercriteria Correlation) Weight Calculator.
 * Measures objective statistical information content (contrast intensity sigma_j
 * and conflict with other criteria 1 - rho_jk) from candidate pool evaluation vectors.
 */
public class CriticWeightCalculator {

  public static final int MIN_POOL_SIZE = 10;
  public static final double EPSILON = 1e-6;

  /**
   * Calculates statistical weights q_j for the 4 memory criteria.
   *
   * @param matrix List of 4-element vectors [SM1, SM2, SM3, SM4] across all pool candidates.
   * @return Optional containing double[4] normalized weights, or empty if pool is insufficient.
   */
  public Optional<double[]> calculate(List<double[]> matrix) {
    if (matrix == null || matrix.size() < MIN_POOL_SIZE) {
      return Optional.empty();
    }

    int n = matrix.size();
    int m = 4; // 4 Memories: SM1, SM2, SM3, SM4

    // 1. Calculate column means
    double[] mean = new double[m];
    for (double[] row : matrix) {
      if (row == null || row.length < m) {
        return Optional.empty();
      }
      for (int j = 0; j < m; j++) {
        mean[j] += row[j];
      }
    }
    for (int j = 0; j < m; j++) {
      mean[j] /= n;
    }

    // 2. Calculate column standard deviations (sigma_j)
    double[] sigma = new double[m];
    for (double[] row : matrix) {
      for (int j = 0; j < m; j++) {
        double diff = row[j] - mean[j];
        sigma[j] += diff * diff;
      }
    }
    double totalSigma = 0.0;
    for (int j = 0; j < m; j++) {
      sigma[j] = Math.sqrt(sigma[j] / (n - 1));
      totalSigma += sigma[j];
    }

    // If total standard deviation across criteria is negligible, pool has no contrast
    if (totalSigma < EPSILON) {
      return Optional.empty();
    }

    // 3. Calculate Pearson correlation matrix (rho_jk)
    double[][] cov = new double[m][m];
    for (double[] row : matrix) {
      for (int j = 0; j < m; j++) {
        for (int k = 0; k < m; k++) {
          cov[j][k] += (row[j] - mean[j]) * (row[k] - mean[k]);
        }
      }
    }

    double[][] rho = new double[m][m];
    for (int j = 0; j < m; j++) {
      for (int k = 0; k < m; k++) {
        if (j == k) {
          rho[j][k] = 1.0;
        } else if (sigma[j] > EPSILON && sigma[k] > EPSILON) {
          rho[j][k] = cov[j][k] / ((n - 1) * sigma[j] * sigma[k]);
          // Clamp to [-1, 1] against numerical imprecision
          rho[j][k] = Math.max(-1.0, Math.min(1.0, rho[j][k]));
        } else {
          rho[j][k] = 0.0;
        }
      }
    }

    // 4. Calculate information content C_j = sigma_j * sum(1 - rho_jk)
    double[] c = new double[m];
    double totalC = 0.0;
    for (int j = 0; j < m; j++) {
      double conflictSum = 0.0;
      for (int k = 0; k < m; k++) {
        conflictSum += (1.0 - rho[j][k]);
      }
      c[j] = sigma[j] * conflictSum;
      totalC += c[j];
    }

    if (totalC < EPSILON) {
      return Optional.empty();
    }

    // 5. Normalize statistical weights q_j
    double[] q = new double[m];
    for (int j = 0; j < m; j++) {
      q[j] = c[j] / totalC;
    }

    return Optional.of(q);
  }
}
