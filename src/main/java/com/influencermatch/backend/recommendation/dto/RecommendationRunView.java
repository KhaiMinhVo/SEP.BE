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
    String status,
    int candidatesCount,
    String queryBrief,
    LocalDateTime createdAt) {
  public static RecommendationRunView from(RecommendationRun r) {
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
        sanitize(r.getScoringSnapshot()),
        r.getStatus(),
        r.getCandidatesCount(),
        r.getQueryBrief(),
        r.getCreatedAt());
  }

  public static JsonNode sanitize(JsonNode snapshot) {
    if (snapshot == null || snapshot.isNull()) return NullNode.instance;
    var result = JsonNodeFactory.instance.arrayNode();
    if (!snapshot.isArray()) return result;
    for (var row : snapshot) {
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
                  "isProvisional"));
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
                    "scoringPolicyVersion"));
        out.set("scoreBreakdown", scores);
      }
      if (row.has("reasoning"))
        out.set(
            "reasoning",
            copy(
                row.path("reasoning"),
                Set.of("summary", "reasons", "explanationAvailable", "evidenceWarnings")));
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
