package com.influencermatch.backend.recommendation.research.validation;

import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import java.util.Map;
import java.util.Set;

public final class ScorerInputBoundary {

  private static final Set<String> FORBIDDEN_ORACLE_OR_LATENT_KEYS = Set.of(
      "oracle",
      "oracleGroundTruth",
      "syntheticExpectedFutureUtility",
      "realisedOutcomeValue",
      "humanSuitabilityGrade",
      "exposedToBrand",
      "selectedByBrand",
      "trueSourceReliability",
      "latentCreatorQuality",
      "futureOutcome",
      "futureRevisions"
  );

  private ScorerInputBoundary() {}

  public static CampaignCreatorSnapshot acceptForScorer(Map<String, Object> rawPayload) {
    if (rawPayload == null) {
      throw new IllegalArgumentException("Scorer input payload must not be null");
    }
    rejectForbiddenKeys(rawPayload);
    String json = ResearchJsonMapper.toDeterministicJson(rawPayload);
    CampaignCreatorSnapshot snapshot = ResearchJsonMapper.fromJson(json, CampaignCreatorSnapshot.class);
    ResearchContractValidator.validateSnapshot(snapshot);
    return snapshot;
  }

  public static CampaignCreatorSnapshot acceptForScorer(CampaignCreatorSnapshot snapshot) {
    if (snapshot == null) {
      throw new IllegalArgumentException("Snapshot must not be null");
    }
    ResearchContractValidator.validateSnapshot(snapshot);
    return snapshot;
  }

  public static ExtendedCampaignSnapshot acceptForScorer(ExtendedCampaignSnapshot extended) {
    if (extended == null) {
      throw new IllegalArgumentException("ExtendedCampaignSnapshot must not be null");
    }
    ResearchContractValidator.validateExtendedSnapshot(extended);
    return extended;
  }

  @SuppressWarnings("unchecked")
  private static void rejectForbiddenKeys(Object node) {
    if (node instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (FORBIDDEN_ORACLE_OR_LATENT_KEYS.contains(key)) {
          throw new SecurityException("ORACLE_BOUNDARY_VIOLATION: Forbidden key '" + key + "' in scorer input");
        }
        rejectForbiddenKeys(entry.getValue());
      }
    } else if (node instanceof Iterable<?> list) {
      for (Object item : list) {
        rejectForbiddenKeys(item);
      }
    }
  }
}
