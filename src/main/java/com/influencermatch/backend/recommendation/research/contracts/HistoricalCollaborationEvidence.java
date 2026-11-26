package com.influencermatch.backend.recommendation.research.contracts;

import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record HistoricalCollaborationEvidence(
    String schemaVersion,
    UUID collaborationId,
    UUID creatorId,
    UUID masterId,
    UUID brandId,
    UUID campaignId,
    DataOrigin dataOrigin,
    Instant eventTime,
    Instant knownAt,
    int revisionNumber,
    String status,
    MetricObservation rObservation,
    MetricObservation tObservation,
    MetricObservation kObservation,
    MetricObservation m4ReviewScore
) {

  public static final String SCHEMA_VERSION = "research.historical_collaboration.v1";

  public HistoricalCollaborationEvidence {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported HistoricalCollaborationEvidence schemaVersion: " + schemaVersion);
    }
    Objects.requireNonNull(collaborationId, "collaborationId must not be null");
    Objects.requireNonNull(creatorId, "creatorId must not be null");
    Objects.requireNonNull(dataOrigin, "dataOrigin must not be null");
    if (dataOrigin == DataOrigin.HUMAN) {
      throw new IllegalArgumentException(
          "HUMAN suitability judgments must not be stored as HistoricalCollaborationEvidence");
    }
    Objects.requireNonNull(eventTime, "eventTime must not be null");
    Objects.requireNonNull(knownAt, "knownAt must not be null");
    if (knownAt.isBefore(eventTime)) {
      throw new IllegalArgumentException("knownAt cannot precede eventTime");
    }
    if (revisionNumber < 1) {
      throw new IllegalArgumentException("revisionNumber must be >= 1");
    }
  }
}
