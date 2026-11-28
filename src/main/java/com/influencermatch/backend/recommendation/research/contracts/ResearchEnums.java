package com.influencermatch.backend.recommendation.research.contracts;

public final class ResearchEnums {

  private ResearchEnums() {}

  public enum DataOrigin {
    REAL,
    HUMAN,
    SYN
  }

  public enum MetricUnit {
    COUNT,
    DECIMAL_RATIO,
    PERCENT,
    STABILITY_INDEX,
    CURRENCY_USD,
    COSINE_SIMILARITY
  }

  public enum MetricAvailability {
    OBSERVED,
    OBSERVED_ZERO,
    MISSING,
    INVALID,
    NOT_APPLICABLE,
    PARTIAL_OBSERVATION,
    IMPUTED
  }

  public enum MeasurementCompleteness {
    FULL,
    PARTIAL,
    NONE,
    NOT_APPLICABLE
  }

  public enum HardEligibilityStatus {
    ELIGIBLE,
    INELIGIBLE,
    PENDING_VERIFICATION
  }

  public enum OracleGradeSource {
    SYNTHETIC_EXPECTED_UTILITY,
    REALISED_OUTCOME,
    HUMAN_SUITABILITY_GRADE,
    EXPOSURE_SELECTION_INDICATOR
  }
}
