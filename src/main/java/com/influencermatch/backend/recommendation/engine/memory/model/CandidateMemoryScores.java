package com.influencermatch.backend.recommendation.engine.memory.model;

/**
 * Encapsulates the normalized scores [0, 1] across all four memory layers
 * along with their component values and evidence reliability metadata.
 */
public record CandidateMemoryScores(
    double sm1,
    double sm2,
    double sm3,
    double sm4,
    double m1Score100,
    double m2Score100,
    double m3Score100,
    double m4Score100,
    double erNorm,
    double vrNorm,
    double stabNorm,
    int m1SampleSize,
    int m1SuccessCount,
    boolean m1ColdStart,
    int m2PostCount,
    boolean m2StabImputed,
    boolean m3EmbeddingAvailable,
    boolean m4BrandNichesConfigured,
    int m4MatchedTags,
    int m4TotalTags
) {
  public double[] asVector() {
    return new double[] {sm1, sm2, sm3, sm4};
  }
}
