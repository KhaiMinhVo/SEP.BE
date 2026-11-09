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
    JsonNode scoringPolicyParameters,
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
        r.getScoringPolicyParameters() == null
            ? null
            : copy(
                r.getScoringPolicyParameters(),
                com.influencermatch.backend.recommendation.learning.LearningPolicy.parameters()
                    .keySet()),
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
                  "finalScore"));
      if (row.has("scoreBreakdown")) {
        var scores =
            copy(
                row.path("scoreBreakdown"),
                Set.of(
                    "m2Score",
                    "m1Score",
                    "m4Score",
                    "followerScore",
                    "engagementScore",
                    "semanticMatchScore",
                    "nicheMatchScore",
                    "finalScore",
                    "m1Available",
                    "m4Available",
                    "m1UnavailableReason",
                    "m4UnavailableReason",
                    "m1EvidenceCount",
                    "m4EvidenceCount",
                    "m4Confidence",
                    "originalWeights",
                    "effectiveWeights",
                    "scoringPolicyVersion",
                    "m4Cohort",
                    "m4MasterCount",
                    "evidenceCoverage",
                    "evidenceAsOf",
                    "evidenceCutoff"));
        var detail = row.path("scoreBreakdown").path("m1Details");
        if (detail.isObject()) {
          var d = copy(detail, Set.of("scope", "evidenceSupport"));
          for (String key : List.of("review", "timeliness", "kpi"))
            if (detail.has(key))
              d.set(
                  key,
                  copy(
                      detail.path(key),
                      Set.of(
                          "score",
                          "count",
                          "brandCount",
                          "originalWeight",
                          "qualityFactor",
                          "effectiveWeight",
                          "unavailableReason",
                          "available")));
          scores.set("m1Details", d);
        }
        out.set("scoreBreakdown", scores);
      }
      if (row.has("reasoning"))
        out.set(
            "reasoning",
            copy(row.path("reasoning"), Set.of("summary", "reasons", "explanationAvailable")));
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
