package com.influencermatch.backend.recommendation.research.contracts;

import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.OracleGradeSource;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OracleGroundTruthRecord(
    String schemaVersion,
    UUID campaignId,
    UUID creatorId,
    DataOrigin dataOrigin,
    OracleGradeSource labelSource,
    Instant labeledAt,
    Double syntheticExpectedFutureUtility,
    Double realisedOutcomeValue,
    Integer humanSuitabilityGrade,
    Boolean exposedToBrand,
    Boolean selectedByBrand,
    String notes
) {

  public static final String SCHEMA_VERSION = "research.oracle_ground_truth.v1";

  public OracleGroundTruthRecord {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported OracleGroundTruthRecord schemaVersion: " + schemaVersion);
    }
    Objects.requireNonNull(campaignId, "campaignId must not be null");
    Objects.requireNonNull(creatorId, "creatorId must not be null");
    Objects.requireNonNull(dataOrigin, "dataOrigin must not be null");
    Objects.requireNonNull(labelSource, "labelSource must not be null");
    Objects.requireNonNull(labeledAt, "labeledAt must not be null");
  }
}
