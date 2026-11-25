package com.influencermatch.backend.recommendation.research.contracts;

import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CampaignCreatorSnapshot(
    String schemaVersion,
    String snapshotId,
    UUID campaignId,
    String objective,
    List<String> platforms,
    List<String> niches,
    Long followerMin,
    Long followerMax,
    Double budgetUsd,
    Instant scoringAsOf,
    DataOrigin dataOrigin,
    List<ObservedEvidenceRecord> creators,
    List<HistoricalCollaborationEvidence> historicalCollaborations
) {

  public static final String SCHEMA_VERSION = "research.snapshot.v1";

  public CampaignCreatorSnapshot {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported CampaignCreatorSnapshot schemaVersion: " + schemaVersion);
    }
    Objects.requireNonNull(campaignId, "campaignId must not be null");
    Objects.requireNonNull(scoringAsOf, "scoringAsOf must not be null");
    Objects.requireNonNull(dataOrigin, "dataOrigin must not be null");
    platforms = platforms != null ? List.copyOf(platforms) : List.of();
    niches = niches != null ? List.copyOf(niches) : List.of();
    creators = creators != null ? List.copyOf(creators) : List.of();
    historicalCollaborations = historicalCollaborations != null ? List.copyOf(historicalCollaborations) : List.of();
  }
}
