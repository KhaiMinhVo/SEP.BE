package com.influencermatch.backend.recommendation.research.contracts;

import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MeasurementCompleteness;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricUnit;
import java.time.Instant;
import java.util.Objects;

public record MetricObservation(
    Double value,
    MetricUnit unit,
    MetricAvailability availability,
    MeasurementCompleteness measurementCompleteness,
    Integer observedCount,
    Integer totalCount,
    String provenance,
    Instant observedAt,
    Instant knownAt,
    String rawInvalidValue
) {

  public MetricObservation {
    Objects.requireNonNull(unit, "MetricObservation.unit must not be null");
    Objects.requireNonNull(availability, "MetricObservation.availability must not be null");
    Objects.requireNonNull(measurementCompleteness, "MetricObservation.measurementCompleteness must not be null");
    if (provenance == null || provenance.isBlank()) {
      throw new IllegalArgumentException("MetricObservation.provenance must not be blank");
    }
    if (observedCount != null && observedCount < 0) {
      throw new IllegalArgumentException("observedCount cannot be negative");
    }
    if (totalCount != null && totalCount < 0) {
      throw new IllegalArgumentException("totalCount cannot be negative");
    }
    if (observedCount != null && totalCount != null && observedCount > totalCount) {
      throw new IllegalArgumentException("observedCount cannot exceed totalCount");
    }
    if (observedAt != null && knownAt != null && knownAt.isBefore(observedAt)) {
      throw new IllegalArgumentException("knownAt cannot be before observedAt");
    }

    switch (availability) {
      case OBSERVED_ZERO -> {
        if (value == null || !Double.isFinite(value) || value != 0.0) {
          throw new IllegalArgumentException("OBSERVED_ZERO requires finite value == 0.0");
        }
      }
      case OBSERVED, PARTIAL_OBSERVATION, IMPUTED -> {
        if (value == null || !Double.isFinite(value)) {
          throw new IllegalArgumentException(availability + " requires non-null finite value");
        }
        if (availability == MetricAvailability.PARTIAL_OBSERVATION
            && (measurementCompleteness != MeasurementCompleteness.PARTIAL
                || (observedCount != null && totalCount != null && observedCount >= totalCount))) {
          throw new IllegalArgumentException("PARTIAL_OBSERVATION requires PARTIAL completeness and observedCount < totalCount");
        }
      }
      case MISSING, INVALID, NOT_APPLICABLE -> {
        if (value != null) {
          throw new IllegalArgumentException(availability + " must have null value");
        }
      }
    }
  }

  public static MetricObservation observed(
      double value, MetricUnit unit, int observedCount, int totalCount,
      String provenance, Instant observedAt, Instant knownAt) {
    MetricAvailability avail = (value == 0.0) ? MetricAvailability.OBSERVED_ZERO : MetricAvailability.OBSERVED;
    MeasurementCompleteness comp = (observedCount < totalCount)
        ? MeasurementCompleteness.PARTIAL : MeasurementCompleteness.FULL;
    if (comp == MeasurementCompleteness.PARTIAL) {
      avail = MetricAvailability.PARTIAL_OBSERVATION;
    }
    return new MetricObservation(value, unit, avail, comp, observedCount, totalCount, provenance, observedAt, knownAt, null);
  }

  public static MetricObservation missing(
      MetricUnit unit, int totalCount, String provenance, Instant observedAt, Instant knownAt) {
    return new MetricObservation(null, unit, MetricAvailability.MISSING, MeasurementCompleteness.NONE, 0, totalCount, provenance, observedAt, knownAt, null);
  }

  public static MetricObservation invalid(
      MetricUnit unit, String rawInvalidValue, int totalCount, String provenance, Instant observedAt, Instant knownAt) {
    return new MetricObservation(null, unit, MetricAvailability.INVALID, MeasurementCompleteness.NONE, 0, totalCount, provenance, observedAt, knownAt, rawInvalidValue);
  }

  public static MetricObservation notApplicable(
      MetricUnit unit, String provenance, Instant observedAt, Instant knownAt) {
    return new MetricObservation(null, unit, MetricAvailability.NOT_APPLICABLE, MeasurementCompleteness.NOT_APPLICABLE, 0, 0, provenance, observedAt, knownAt, null);
  }
}
