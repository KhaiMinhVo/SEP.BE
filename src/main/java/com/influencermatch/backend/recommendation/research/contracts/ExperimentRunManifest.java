package com.influencermatch.backend.recommendation.research.contracts;

import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExperimentRunManifest(
    String schemaVersion,
    UUID runId,
    String protocolVersion,
    String dgpVersion,
    String featureVersion,
    String scorerVersion,
    String configHash,
    String dataHash,
    String seedPolicy,
    DataOrigin dataOrigin,
    Instant scoringAsOf,
    Map<String, String> environmentProvenance
) {

  public static final String SCHEMA_VERSION = "research.run_manifest.v1";

  public ExperimentRunManifest {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported ExperimentRunManifest schemaVersion: " + schemaVersion);
    }
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(dataOrigin, "dataOrigin must not be null");
    Objects.requireNonNull(scoringAsOf, "scoringAsOf must not be null");
    environmentProvenance = environmentProvenance != null ? Map.copyOf(environmentProvenance) : Map.of();
  }
}
