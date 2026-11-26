package com.influencermatch.backend.recommendation.research.contracts;

import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricUnit;
import java.util.List;
import java.util.Objects;

public record DerivedFeature(
    String featureName,
    Double value,
    MetricUnit unit,
    MetricAvailability availability,
    boolean isImputed,
    String derivationMethod,
    List<String> sourceObservations
) {
  public DerivedFeature {
    if (featureName == null || featureName.isBlank()) {
      throw new IllegalArgumentException("featureName must not be blank");
    }
    Objects.requireNonNull(unit, "unit must not be null");
    Objects.requireNonNull(availability, "availability must not be null");
    if (value != null && !Double.isFinite(value)) {
      throw new IllegalArgumentException("DerivedFeature.value must be finite when present");
    }
    sourceObservations = sourceObservations != null ? List.copyOf(sourceObservations) : List.of();
  }
}
