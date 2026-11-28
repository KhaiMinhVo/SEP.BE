package com.influencermatch.backend.recommendation.research.contracts;

import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned extension (research.observed_context.v1) carrying observed creator niche/category
 * and observed semantic similarity without mutating research.observed_evidence.v1.
 */
public record ObservedCreatorContextExtension(
    String schemaVersion,
    UUID creatorId,
    DataOrigin dataOrigin,
    String niche,
    String category,
    MetricObservation semanticSimilarity,
    Instant observedAt,
    Instant knownAt
) {

  public static final String SCHEMA_VERSION = "research.observed_context.v1";

  public ObservedCreatorContextExtension {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported ObservedCreatorContextExtension schemaVersion: " + schemaVersion);
    }
    Objects.requireNonNull(creatorId, "creatorId must not be null");
    Objects.requireNonNull(dataOrigin, "dataOrigin must not be null");
    Objects.requireNonNull(observedAt, "observedAt must not be null");
    Objects.requireNonNull(knownAt, "knownAt must not be null");
    if (knownAt.isBefore(observedAt)) {
      throw new IllegalArgumentException("knownAt cannot precede observedAt");
    }
  }
}
