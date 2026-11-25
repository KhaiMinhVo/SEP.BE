package com.influencermatch.backend.recommendation.research.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScoringTrace(
    String schemaVersion,
    UUID runId,
    UUID creatorId,
    String scorerVersion,
    Instant scoredAt,
    String readiness,
    List<DerivedFeature> derivedFeatures,
    List<String> traceNotes
) {
  public ScoringTrace {
    if (schemaVersion == null || schemaVersion.isBlank()) {
      throw new IllegalArgumentException("schemaVersion must not be blank");
    }
    derivedFeatures = derivedFeatures != null ? List.copyOf(derivedFeatures) : List.of();
    traceNotes = traceNotes != null ? List.copyOf(traceNotes) : List.of();
  }
}
