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
    int m4TotalTags,
    String m1Availability,
    Double rScore,
    Double tScore,
    Double kScore,
    String rAvailability,
    String tAvailability,
    String kAvailability,
    int eligibleCollaborations,
    int distinctBrandCount,
    double maxBrandShare,
    Double m4LearnedScore,
    Double m4ExplicitScore,
    Double m4EffectiveScore,
    String m4Source
) {
  public CandidateMemoryScores(
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
      int m4TotalTags) {
    this(
        sm1,
        sm2,
        sm3,
        sm4,
        m1Score100,
        m2Score100,
        m3Score100,
        m4Score100,
        erNorm,
        vrNorm,
        stabNorm,
        m1SampleSize,
        m1SuccessCount,
        m1ColdStart,
        m2PostCount,
        m2StabImputed,
        m3EmbeddingAvailable,
        m4BrandNichesConfigured,
        m4MatchedTags,
        m4TotalTags,
        m1SampleSize >= 3 ? "AVAILABLE" : "UNAVAILABLE",
        null,
        null,
        null,
        "UNAVAILABLE",
        "UNAVAILABLE",
        "UNAVAILABLE",
        m1SampleSize,
        0,
        0.0,
        null,
        m4Score100 / 100.0,
        m4Score100 / 100.0,
        "EXPLICIT_FALLBACK");
  }

  public CandidateMemoryScores {
    validateScore(sm1, "sm1");
    validateScore(sm2, "sm2");
    validateScore(sm3, "sm3");
    validateScore(sm4, "sm4");
  }

  private static void validateScore(double score, String name) {
    if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
      throw new IllegalArgumentException(
          name + " must be a finite number in [0.0, 1.0], but got: " + score);
    }
  }

  public double[] asVector() {
    return new double[] {sm1, sm2, sm3, sm4};
  }
}
