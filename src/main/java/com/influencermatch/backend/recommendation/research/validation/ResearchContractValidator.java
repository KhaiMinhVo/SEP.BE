package com.influencermatch.backend.recommendation.research.validation;

import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.HistoricalCollaborationEvidence;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedCreatorContextExtension;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord.PostObservation;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricUnit;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ResearchContractValidator {

  private ResearchContractValidator() {}

  public static void validateObservedEvidence(ObservedEvidenceRecord record) {
    validateObservedMetric("followers", record.followers(), MetricUnit.COUNT);
    validateObservedMetric("avgViews", record.avgViews(), MetricUnit.COUNT);
    validateObservedMetric("avgLikes", record.avgLikes(), MetricUnit.COUNT);
    validateObservedMetric("avgComments", record.avgComments(), MetricUnit.COUNT);
    validateObservedMetric("avgShares", record.avgShares(), MetricUnit.COUNT);
    validateObservedMetric("engagementRate", record.engagementRate(), MetricUnit.DECIMAL_RATIO);

    for (PostObservation post : record.postWindowMetrics()) {
      validateObservedMetric("post.views", post.views(), MetricUnit.COUNT);
      validateObservedMetric("post.likes", post.likes(), MetricUnit.COUNT);
      validateObservedMetric("post.comments", post.comments(), MetricUnit.COUNT);
      validateObservedMetric("post.shares", post.shares(), MetricUnit.COUNT);
    }
  }

  public static void validateSnapshot(CampaignCreatorSnapshot snapshot) {
    Instant cutoff = snapshot.scoringAsOf();
    for (ObservedEvidenceRecord creator : snapshot.creators()) {
      if (creator.dataOrigin() != snapshot.dataOrigin()) {
        throw new IllegalArgumentException(
            "DATA_ORIGIN_MIXING: Creator origin " + creator.dataOrigin() + " != snapshot origin " + snapshot.dataOrigin());
      }
      if (creator.knownAt().isAfter(cutoff) || creator.observedAt().isAfter(cutoff)) {
        throw new IllegalArgumentException(
            "TEMPORAL_LEAKAGE: Creator " + creator.creatorId() + " knownAt/observedAt exceeds scoringAsOf");
      }
      for (PostObservation post : creator.postWindowMetrics()) {
        if (post.postedAt() != null && post.postedAt().isAfter(cutoff)) {
          throw new IllegalArgumentException(
              "TEMPORAL_LEAKAGE: Post " + post.platformPostId() + " postedAt exceeds scoringAsOf");
        }
      }
      validateObservedEvidence(creator);
    }

    for (HistoricalCollaborationEvidence hist : snapshot.historicalCollaborations()) {
      if (hist.dataOrigin() != snapshot.dataOrigin()) {
        throw new IllegalArgumentException(
            "DATA_ORIGIN_MIXING: Historical origin " + hist.dataOrigin() + " != snapshot origin " + snapshot.dataOrigin());
      }
      if (hist.knownAt().isAfter(cutoff) || hist.eventTime().isAfter(cutoff)) {
        throw new IllegalArgumentException(
            "TEMPORAL_LEAKAGE: Collaboration " + hist.collaborationId() + " exceeds scoringAsOf");
      }
    }
  }

  public static void validateExtendedSnapshot(ExtendedCampaignSnapshot extended) {
    CampaignCreatorSnapshot base = extended.baseSnapshot();
    validateSnapshot(base);
    Instant cutoff = base.scoringAsOf();
    for (ObservedCreatorContextExtension ext : extended.contextExtensions().values()) {
      if (ext.dataOrigin() != base.dataOrigin()) {
        throw new IllegalArgumentException(
            "DATA_ORIGIN_MIXING: ContextExtension origin " + ext.dataOrigin() + " != snapshot origin " + base.dataOrigin());
      }
      if (ext.knownAt().isAfter(cutoff) || ext.observedAt().isAfter(cutoff)) {
        throw new IllegalArgumentException(
            "TEMPORAL_LEAKAGE: ContextExtension for " + ext.creatorId() + " exceeds scoringAsOf");
      }
      if (ext.semanticSimilarity() != null) {
        validateObservedMetric("semanticSimilarity", ext.semanticSimilarity(), MetricUnit.COSINE_SIMILARITY);
        if (ext.semanticSimilarity().value() != null
            && (ext.semanticSimilarity().value() < -1.0 || ext.semanticSimilarity().value() > 1.0)) {
          throw new IllegalArgumentException("semanticSimilarity cosine must be in [-1.0, 1.0]");
        }
      }
    }
  }

  public static List<HistoricalCollaborationEvidence> replayHistoricalAt(
      List<HistoricalCollaborationEvidence> records, Instant scoringAsOf) {
    Map<UUID, HistoricalCollaborationEvidence> latestByCollab = new LinkedHashMap<>();
    records.stream()
        .filter(r -> !r.knownAt().isAfter(scoringAsOf) && !r.eventTime().isAfter(scoringAsOf))
        .sorted(Comparator.comparingInt(HistoricalCollaborationEvidence::revisionNumber))
        .forEach(r -> latestByCollab.put(r.collaborationId(), r));
    return List.copyOf(latestByCollab.values());
  }

  private static void validateObservedMetric(String field, MetricObservation obs, MetricUnit expectedUnit) {
    if (obs == null) {
      return;
    }
    if (obs.availability() == MetricAvailability.IMPUTED) {
      throw new IllegalArgumentException("IMPUTED_IN_OBSERVED_EVIDENCE: " + field + " cannot use IMPUTED availability");
    }
    if (obs.unit() != expectedUnit) {
      throw new IllegalArgumentException(
          "UNIT_CONTRACT_VIOLATION: " + field + " expected " + expectedUnit + " but received " + obs.unit());
    }
  }
}
