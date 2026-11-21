package com.influencermatch.backend.cooperation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.exception.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RecommendationContactPolicyTest {
  final RecommendationRunRepository runs = mock(RecommendationRunRepository.class);
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final RecommendationContactPolicy policy = new RecommendationContactPolicy(runs, db);
  final UUID campaign = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
  final ObjectMapper json = new ObjectMapper();
  RecommendationRun run(UUID owner, UUID... ids) {
    return RecommendationRun.builder().id(UUID.randomUUID()).campaign(Campaign.builder().id(owner).build()).status("COMPLETED")
        .scoringSnapshot(json.valueToTree(Map.of("candidates", Arrays.stream(ids).map(id -> Map.of("creatorId", id, "recommendationTier", "LIMITED_HISTORY", "isScoreReady", false)).toList()))).build();
  }
  void active() { when(db.queryForList(anyString(), eq(UUID.class), eq(campaign))).thenReturn(List.of(a,b)); }
  @Test void unpublishedReferenceIsEligibleAndOneRunMustCoverEveryChannel() {
    active(); var saved = run(campaign,a,b);
    when(runs.findByCampaignIdOrderByCreatedAtDesc(campaign)).thenReturn(List.of(saved));
    assertEquals(saved.getId(), policy.requireEvaluated(campaign,List.of(a,b),null));
  }
  @Test void cannotMergeTwoRunsToSatisfyCoverage() {
    active(); when(runs.findByCampaignIdOrderByCreatedAtDesc(campaign)).thenReturn(List.of(run(campaign,a),run(campaign,b)));
    assertThrows(BusinessException.class, () -> policy.requireEvaluated(campaign,List.of(a,b),null));
  }
  @Test void rejectedForeignAndFailedRunCannotAuthorizeOutreach() {
    active(); var foreign = run(UUID.randomUUID(),a); when(runs.findById(foreign.getId())).thenReturn(Optional.of(foreign));
    assertThrows(BusinessException.class, () -> policy.requireEvaluated(campaign,List.of(a),foreign.getId()));
    var failed = run(campaign,a); failed.setStatus("FAILED"); when(runs.findById(failed.getId())).thenReturn(Optional.of(failed));
    assertThrows(BusinessException.class, () -> policy.requireEvaluated(campaign,List.of(a),failed.getId()));
  }
  @Test void removedShortlistChannelIsBlockedEvenWithHistoricalScore() {
    when(db.queryForList(anyString(), eq(UUID.class), eq(campaign))).thenReturn(List.of(a));
    assertThrows(BusinessException.class, () -> policy.requireEvaluated(campaign,List.of(a,b),null));
    verifyNoInteractions(runs);
  }
  @Test void excludedCandidateCannotAuthorizeOutreach() {
    var snapshot = json.valueToTree(Map.of("recommendations",List.of(Map.of("creatorId",a)), "excludedCandidates",List.of(Map.of("creatorId",a))));
    assertTrue(RecommendationContactPolicy.eligibleIds(snapshot).isEmpty());
  }
  @Test void legacyArrayAndProvisionalPoolAreReadWithoutRequiringPublishedScores() {
    assertEquals(Set.of(a),RecommendationContactPolicy.eligibleIds(json.valueToTree(List.of(Map.of("creatorId",a)))));
    assertEquals(Set.of(b),RecommendationContactPolicy.eligibleIds(json.valueToTree(Map.of("provisionalRecommendations",List.of(Map.of("creatorId",b))))));
  }
  @Test void existingNegotiationReusesHistoricalEvaluationWithoutRequiringShortlistAgain() {
    var saved = run(campaign,a,b);
    when(runs.findByCampaignIdOrderByCreatedAtDesc(campaign)).thenReturn(List.of(saved));
    assertEquals(saved.getId(),policy.requirePreviouslyEvaluated(campaign,List.of(a,b)));
    verifyNoInteractions(db);
  }
  @Test void topNAndMasterDedupDoNotErasePersistedEvaluationCoverage() {
    var snapshot = json.valueToTree(Map.of("recommendations",List.of(Map.of("creatorId",a)),"runMetadata",Map.of("evaluatedCreatorIds",List.of(a,b))));
    assertEquals(Set.of(a,b),RecommendationContactPolicy.eligibleIds(snapshot));
  }
}
