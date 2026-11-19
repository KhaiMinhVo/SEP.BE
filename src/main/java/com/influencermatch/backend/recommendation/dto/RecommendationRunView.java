package com.influencermatch.backend.recommendation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.influencermatch.backend.campaign.model.RecommendationRun;
import java.time.LocalDateTime;
import java.util.*;

/** Public history projection. Internal evidence links and JPA User graphs never cross this API. */
public record RecommendationRunView(
    UUID id,
    UUID campaignId,
    UUID brandProfileId,
    int contextVersion,
    UUID campaignContextId,
    UUID brandContextId,
    Integer brandContextVersion,
    UUID embeddingContextId,
    UUID embeddingJobId,
    String embeddingModel,
    String embeddingModelVersion,
    String candidateSource,
    String scoringPolicyVersion,
    JsonNode scoringSnapshot,
    JsonNode runMetadata,
    String status,
    int candidatesCount,
    String queryBrief,
    LocalDateTime createdAt) {

  public RecommendationRunView(
      UUID id,
      UUID campaignId,
      UUID brandProfileId,
      int contextVersion,
      UUID campaignContextId,
      UUID brandContextId,
      Integer brandContextVersion,
      UUID embeddingContextId,
      UUID embeddingJobId,
      String embeddingModel,
      String embeddingModelVersion,
      String candidateSource,
      String scoringPolicyVersion,
      JsonNode scoringSnapshot,
      String status,
      int candidatesCount,
      String queryBrief,
      LocalDateTime createdAt) {
    this(
        id,
        campaignId,
        brandProfileId,
        contextVersion,
        campaignContextId,
        brandContextId,
        brandContextVersion,
        embeddingContextId,
        embeddingJobId,
        embeddingModel,
        embeddingModelVersion,
        candidateSource,
        scoringPolicyVersion,
        scoringSnapshot,
        null,
        status,
        candidatesCount,
        queryBrief,
        createdAt);
  }

  public static RecommendationRunView from(RecommendationRun r) {
    JsonNode rawSnapshot = r.getScoringSnapshot();
    return new RecommendationRunView(
        r.getId(),
        r.getCampaign().getId(),
        r.getBrandProfile().getId(),
        r.getContextVersion(),
        r.getCampaignContextId(),
        r.getBrandContextId(),
        r.getBrandContextVersion(),
        r.getEmbeddingContextId(),
        r.getEmbeddingJobId(),
        r.getEmbeddingModel(),
        r.getEmbeddingModelVersion(),
        r.getCandidateSource(),
        r.getScoringPolicyVersion(),
        sanitize(rawSnapshot),
        extractRunMetadata(rawSnapshot),
        r.getStatus(),
        r.getCandidatesCount(),
        r.getQueryBrief(),
        r.getCreatedAt());
  }

  public static JsonNode extractRunMetadata(JsonNode snapshot) {
    if (snapshot == null || !snapshot.isObject() || !snapshot.has("runMetadata")) {
      return null;
    }
    JsonNode meta = snapshot.get("runMetadata");
    if (meta == null || meta.isNull() || !meta.isObject()) {
      return null;
    }
    return meta.deepCopy();
  }

  public static JsonNode sanitize(JsonNode snapshot) {
    if (snapshot == null || snapshot.isNull()) return NullNode.instance;
    var result = JsonNodeFactory.instance.arrayNode();
    JsonNode sourceArray = snapshot;
    if (snapshot.isObject()) {
      if (snapshot.has("candidates")) {
        sourceArray = snapshot.get("candidates");
      } else if (snapshot.has("recommendations")) {
        sourceArray = snapshot.get("recommendations");
      }
    }
    if (!sourceArray.isArray()) return result;
    for (var row : sourceArray) {
      var out =
          copy(
              row,
              Set.of(
                  "creatorId",
                  "masterId",
                  "platform",
                  "displayName",
                  "userName",
                  "avatarUrl",
                  "followers",
                  "avgViews",
                  "avgLikes",
                  "engagementRate",
                  "niche",
                  "category",
                  "creatorType",
                  "claimed",
                  "isClaimed",
                  "inShortlist",
                  "isInShortlist",
                  "shortlistStatus",
                  "finalScore",
                  "priceStatus",
                  "scoreReady",
                  "isScoreReady",
                  "provisional",
                  "isProvisional",
                  "recommendationTier"));
      if (row.has("scoreBreakdown")) {
        var scores =
            copy(
                row.path("scoreBreakdown"),
                Set.of(
                    "m3Score",
                    "m2Score",
                    "m1Score",
                    "m4Score",
                    "followerScore",
                    "engagementScore",
                    "semanticMatchScore",
                    "nicheMatchScore",
                    "finalScore",
                    "scoringPolicyVersion",
                    "m1Availability",
                    "rScore",
                    "tScore",
                    "kScore",
                    "eligibleEvaluatedCollaborations",
                    "distinctBrandCount",
                    "maxBrandShare",
                    "m4LearnedScore",
                    "m4ExplicitScore",
                    "m4EffectiveScore",
                    "m4Source",
                    "confidenceScore",
                    "confidenceTier"));
        out.set("scoreBreakdown", scores);
      }
      if (row.has("reasoning"))
        out.set(
            "reasoning",
            copy(
                row.path("reasoning"),
                Set.of("summary", "reasons", "explanationAvailable", "evidenceWarnings", "confidenceScore", "confidenceTier")));
      result.add(out);
    }
    return result;
  }

  private static ObjectNode copy(JsonNode node, Set<String> keys) {
    var out = JsonNodeFactory.instance.objectNode();
    for (String key : keys) if (node.has(key)) out.set(key, node.get(key).deepCopy());
    return out;
  }
}
