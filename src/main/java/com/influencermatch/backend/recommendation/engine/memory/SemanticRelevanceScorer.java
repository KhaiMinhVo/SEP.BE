package com.influencermatch.backend.recommendation.engine.memory;

/**
 * Phase 1: SM3 - Current Campaign Context (Semantic Relevance Scorer).
 * Maps vector cosine similarity c in [-1, 1] to normalized score [0, 1]:
 * SM3 = (clamp(c, -1, 1) + 1.0) / 2.0.
 */
public class SemanticRelevanceScorer {

  public record Result(
      double sm3Norm,
      double score100,
      boolean hasEmbedding
  ) {}

  public Result score(Double rawCosine) {
    boolean hasEmbedding = (rawCosine != null);
    double cosine = hasEmbedding ? rawCosine : 0.0;
    double clamped = Math.max(-1.0, Math.min(1.0, cosine));
    double sm3Norm = (clamped + 1.0) / 2.0;
    double score100 = sm3Norm * 100.0;

    return new Result(sm3Norm, score100, hasEmbedding);
  }
}
