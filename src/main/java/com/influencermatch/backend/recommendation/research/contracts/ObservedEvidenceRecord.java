package com.influencermatch.backend.recommendation.research.contracts;

import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ObservedEvidenceRecord(
    String schemaVersion,
    UUID creatorId,
    UUID masterId,
    String platform,
    DataOrigin dataOrigin,
    String snapshotId,
    Instant observedAt,
    Instant knownAt,
    MetricObservation followers,
    MetricObservation avgViews,
    MetricObservation avgLikes,
    MetricObservation avgComments,
    MetricObservation avgShares,
    MetricObservation engagementRate,
    List<PostObservation> postWindowMetrics
) {

  public static final String SCHEMA_VERSION = "research.observed_evidence.v1";

  public record PostObservation(
      String platformPostId,
      Instant postedAt,
      MetricObservation views,
      MetricObservation likes,
      MetricObservation comments,
      MetricObservation shares
  ) {}

  public ObservedEvidenceRecord {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported ObservedEvidenceRecord schemaVersion: " + schemaVersion);
    }
    Objects.requireNonNull(creatorId, "creatorId must not be null");
    Objects.requireNonNull(dataOrigin, "dataOrigin must not be null");
    Objects.requireNonNull(observedAt, "observedAt must not be null");
    Objects.requireNonNull(knownAt, "knownAt must not be null");
    if (knownAt.isBefore(observedAt)) {
      throw new IllegalArgumentException("knownAt cannot precede observedAt");
    }
    postWindowMetrics = postWindowMetrics != null ? List.copyOf(postWindowMetrics) : List.of();
  }
}
