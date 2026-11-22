package com.influencermatch.backend.cooperation;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.exception.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class RecommendationContactPolicy {
  final RecommendationRunRepository runs;
  final JdbcTemplate db;

  public UUID requireEvaluated(UUID campaign, Collection<UUID> creators, UUID requestedRun) {
    return require(campaign, creators, requestedRun, true);
  }
  public UUID requirePreviouslyEvaluated(UUID campaign, Collection<UUID> creators) {
    return require(campaign, creators, null, false);
  }
  private UUID require(UUID campaign, Collection<UUID> creators, UUID requestedRun, boolean requireActive) {
    var ids = new HashSet<>(creators);
    if (ids.isEmpty() || ids.contains(null)) throw required();
    if (requireActive) {
      var active = new HashSet<>(db.queryForList("select creator_id from shortlist_item where campaign_id=? and status<>'REMOVED'", UUID.class, campaign));
      if (!active.containsAll(ids)) throw required();
    }
    var candidates = requestedRun == null ? runs.findByCampaignIdOrderByCreatedAtDesc(campaign)
        : runs.findById(requestedRun).map(List::of).orElse(List.of());
    for (var run : candidates)
      if (run.getCampaign() != null && campaign.equals(run.getCampaign().getId()) && "COMPLETED".equals(run.getStatus())
          && eligibleIds(run.getScoringSnapshot()).containsAll(ids)) return run.getId();
    throw required();
  }

  static Set<UUID> eligibleIds(JsonNode snapshot) {
    var ids = new HashSet<UUID>();
    if (snapshot == null) return ids;
    if (snapshot.isArray()) read(snapshot, ids);
    else if (snapshot.has("candidates")) read(snapshot.path("candidates"), ids);
    else { read(snapshot.path("recommendations"), ids); read(snapshot.path("provisionalRecommendations"), ids); }
    for (var id : snapshot.path("runMetadata").path("evaluatedCreatorIds")) {
      try { ids.add(UUID.fromString(id.asText())); } catch (IllegalArgumentException ignored) { }
    }
    for (var row : snapshot.path("excludedCandidates")) {
      try { ids.remove(UUID.fromString(row.path("creatorId").asText())); } catch (IllegalArgumentException ignored) { }
    }
    return ids;
  }
  static void read(JsonNode rows, Set<UUID> ids) {
    if (!rows.isArray()) return;
    for (var row : rows) {
      String tier = row.path("recommendationTier").asText();
      if ((!tier.isEmpty() && !Set.of("HISTORY_SUPPORTED", "LIMITED_HISTORY", "LIMITED_STABILITY").contains(tier)) || row.path("excluded").asBoolean(false)) continue;
      try { ids.add(UUID.fromString(row.path("creatorId").asText())); } catch (IllegalArgumentException ignored) { }
    }
  }
  private BusinessException required() {
    return new BusinessException(ErrorCode.RECOMMENDATION_REQUIRED, "Select active shortlist channels evaluated together in one completed recommendation run before outreach.");
  }
}
