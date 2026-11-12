package com.influencermatch.backend.recommendation.engine.topsis;

import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.engine.memory.model.CandidateMemoryScores;
import com.influencermatch.backend.recommendation.engine.topsis.model.TopsisResult;
import java.util.Comparator;
import java.util.UUID;

/**
 * Phase 4: Fixed-Ideal Multi-Criteria TOPSIS Ranking Engine.
 * Evaluates candidate proximity to fixed positive ideal A+ = (1, 1, 1, 1)
 * and fixed negative ideal A- = (0, 0, 0, 0) using blended objective weights w_j.
 */
public class TopsisRankingEngine {

  public static final double EPSILON = 1e-9;

  /**
   * Computes TOPSIS distances and closeness coefficient for a candidate's memory scores.
   *
   * @param scores Memory scores [SM1, SM2, SM3, SM4] in [0, 1].
   * @param weights Blended criteria weights w_j.
   * @return TopsisResult containing D+, D-, closeness T, and MatchScore (100 * T).
   */
  public TopsisResult compute(CandidateMemoryScores scores, EngineWeights weights) {
    return compute(scores.sm1(), scores.sm2(), scores.sm3(), scores.sm4(), weights);
  }

  public TopsisResult compute(
      double sm1, double sm2, double sm3, double sm4, EngineWeights weights) {

    if (weights == null) {
      throw new IllegalArgumentException("TOPSIS_INVALID_INPUT: EngineWeights must not be null.");
    }

    // 1. Kiểm tra tính hợp lệ dữ liệu toán học: các điểm thành phần phải là số thực hữu hạn trong [0, 1]
    if (Double.isNaN(sm1) || Double.isNaN(sm2) || Double.isNaN(sm3) || Double.isNaN(sm4) ||
        Double.isInfinite(sm1) || Double.isInfinite(sm2) || Double.isInfinite(sm3) || Double.isInfinite(sm4) ||
        sm1 < -1e-6 || sm1 > 1.0 + 1e-6 || sm2 < -1e-6 || sm2 > 1.0 + 1e-6 ||
        sm3 < -1e-6 || sm3 > 1.0 + 1e-6 || sm4 < -1e-6 || sm4 > 1.0 + 1e-6) {
      throw new IllegalArgumentException(
          String.format("TOPSIS_INVALID_INPUT: Memory scores must be finite numbers in [0, 1]. Received: [%f, %f, %f, %f]",
              sm1, sm2, sm3, sm4));
    }

    // 2. Kiểm tra tính hợp lệ của trọng số: không âm, hữu hạn và tổng bằng 1.0 (dung sai 1e-3)
    double w1 = weights.w1();
    double w2 = weights.w2();
    double w3 = weights.w3();
    double w4 = weights.w4();

    if (Double.isNaN(w1) || Double.isNaN(w2) || Double.isNaN(w3) || Double.isNaN(w4) ||
        Double.isInfinite(w1) || Double.isInfinite(w2) || Double.isInfinite(w3) || Double.isInfinite(w4) ||
        w1 < 0.0 || w2 < 0.0 || w3 < 0.0 || w4 < 0.0) {
      throw new IllegalArgumentException(
          String.format("TOPSIS_INVALID_INPUT: Criteria weights must be non-negative finite numbers. Received: [%f, %f, %f, %f]",
              w1, w2, w3, w4));
    }

    double sumW = w1 + w2 + w3 + w4;
    if (Math.abs(sumW - 1.0) > 1e-3) {
      throw new IllegalArgumentException(
          String.format("TOPSIS_INVALID_INPUT: Criteria weights sum must equal 1.0 (within 1e-3). Received sum: %f",
              sumW));
    }

    // Clamp nhẹ sai số làm tròn số thực
    sm1 = Math.max(0.0, Math.min(1.0, sm1));
    sm2 = Math.max(0.0, Math.min(1.0, sm2));
    sm3 = Math.max(0.0, Math.min(1.0, sm3));
    sm4 = Math.max(0.0, Math.min(1.0, sm4));

    // D+ to fixed ideal A+ = (1, 1, 1, 1)
    double dPos = Math.sqrt(
        Math.pow(w1 * (1.0 - sm1), 2) +
        Math.pow(w2 * (1.0 - sm2), 2) +
        Math.pow(w3 * (1.0 - sm3), 2) +
        Math.pow(w4 * (1.0 - sm4), 2)
    );

    // D- to fixed ideal A- = (0, 0, 0, 0)
    double dNeg = Math.sqrt(
        Math.pow(w1 * sm1, 2) +
        Math.pow(w2 * sm2, 2) +
        Math.pow(w3 * sm3, 2) +
        Math.pow(w4 * sm4, 2)
    );

    double denom = dPos + dNeg;
    if (denom <= EPSILON) {
      throw new IllegalStateException(
          "TOPSIS_SINGULARITY_VIOLATION: D+ + D- cannot be <= 0 under unit-sum non-negative weights");
    }
    double closeness = dNeg / denom;
    double matchScore = closeness * 100.0;

    return new TopsisResult(dPos, dNeg, closeness, matchScore);
  }

  /**
   * Deterministic Comparator for ranking candidates:
   * 1. MatchScore descending
   * 2. Semantic score (SM3) descending
   * 3. Public performance (SM2) descending
   * 4. Creator ID ascending
   */
  public Comparator<RankableCandidate> deterministicComparator() {
    return (a, b) -> {
      int cmpScore = Double.compare(b.matchScore(), a.matchScore());
      if (cmpScore != 0) return cmpScore;

      int cmpSm3 = Double.compare(b.memoryScores().sm3(), a.memoryScores().sm3());
      if (cmpSm3 != 0) return cmpSm3;

      int cmpSm2 = Double.compare(b.memoryScores().sm2(), a.memoryScores().sm2());
      if (cmpSm2 != 0) return cmpSm2;

      return a.creatorId().compareTo(b.creatorId());
    };
  }

  public interface RankableCandidate {
    UUID creatorId();
    double matchScore();
    CandidateMemoryScores memoryScores();
  }
}
