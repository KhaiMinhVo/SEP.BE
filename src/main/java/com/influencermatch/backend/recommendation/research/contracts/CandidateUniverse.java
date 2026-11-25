package com.influencermatch.backend.recommendation.research.contracts;

import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CandidateUniverse(
    String schemaVersion,
    UUID campaignId,
    String snapshotId,
    DataOrigin dataOrigin,
    String inclusionSource,
    List<CandidateEntry> entries
) {

  public static final String SCHEMA_VERSION = "research.candidate_universe.v1";

  public record CandidateEntry(
      UUID creatorId,
      UUID masterId,
      String platform,
      HardEligibilityStatus hardEligibility,
      List<String> eligibilityReasons
  ) {
    public CandidateEntry {
      Objects.requireNonNull(creatorId, "creatorId must not be null");
      Objects.requireNonNull(hardEligibility, "hardEligibility must not be null");
      eligibilityReasons = eligibilityReasons != null ? List.copyOf(eligibilityReasons) : List.of();
    }
  }

  public CandidateUniverse {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported CandidateUniverse schemaVersion: " + schemaVersion);
    }
    Objects.requireNonNull(campaignId, "campaignId must not be null");
    Objects.requireNonNull(dataOrigin, "dataOrigin must not be null");
    if (inclusionSource == null || inclusionSource.isBlank()) {
      throw new IllegalArgumentException("inclusionSource must not be blank");
    }
    entries = entries != null ? List.copyOf(entries) : List.of();

    Set<UUID> seenCreators = new HashSet<>();
    Set<String> seenMasterPlatform = new HashSet<>();
    for (CandidateEntry entry : entries) {
      if (!seenCreators.add(entry.creatorId())) {
        throw new IllegalArgumentException("Duplicate creatorId in CandidateUniverse: " + entry.creatorId());
      }
      if (entry.masterId() != null && entry.platform() != null) {
        String key = entry.masterId() + ":" + entry.platform().trim().toUpperCase();
        if (!seenMasterPlatform.add(key)) {
          throw new IllegalArgumentException("Duplicate (masterId, platform) identity in CandidateUniverse: " + key);
        }
      }
    }
  }
}
