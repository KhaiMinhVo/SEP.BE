package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.recommendation.engine.memory.M4ReviewHistory;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/** Real HTTP controllers, repositories, pgvector and scorer. Only external AI transport is stubbed. */
@org.springframework.test.context.TestPropertySource(properties = "application.campaign-embedding.enabled=false")
class M4TemporalPostgresIntegrationTest extends ExecutionScenarioSupport {
  @MockBean AiServiceClient externalAi;
  @Autowired com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore embeddings;

  UUID brandProfile() {
    return jdbc.queryForObject("select brand_profile_id from campaign where id=?", UUID.class, campaign);
  }

  void completeFirst() throws Exception {
    ready(); accept();
    request(brand, root + "/review", review(), 200);
  }

  UUID reviewId() {
    return jdbc.queryForObject("select id from creator_review where collaboration_id=?", UUID.class, collaboration);
  }

  @Test void temporalBoundariesAreInclusiveAndFutureOldMissingAndCurrentSourcesAreExcluded() throws Exception {
    completeFirst();
    UUID target = UUID.randomUUID();
    LocalDateTime asOf = LocalDateTime.of(2026,11,22,12,0), cutoff = asOf.minusMonths(24);
    UUID review = reviewId();
    for (LocalDateTime boundary : List.of(cutoff, asOf)) {
      times(boundary, boundary);
      assertThat(M4ReviewHistory.select(jdbc, brandProfile(), target, asOf))
          .extracting(r -> r.reviewId()).containsExactly(review);
    }
    times(cutoff.minusNanos(1000), asOf);
    assertThat(M4ReviewHistory.select(jdbc, brandProfile(), target, asOf)).isEmpty();
    times(asOf, cutoff.minusNanos(1000));
    assertThat(M4ReviewHistory.select(jdbc, brandProfile(), target, asOf)).isEmpty();
    times(asOf.plusNanos(1000), asOf);
    assertThat(M4ReviewHistory.select(jdbc, brandProfile(), target, asOf)).isEmpty();
    times(asOf, asOf.plusNanos(1000));
    assertThat(M4ReviewHistory.select(jdbc, brandProfile(), target, asOf)).isEmpty();
    times(asOf, asOf);
    assertThat(M4ReviewHistory.select(jdbc, brandProfile(), campaign, asOf)).isEmpty();
    assertThat(M4ReviewHistory.select(jdbc, UUID.randomUUID(), target, asOf)).isEmpty();
    jdbc.update("update collaboration set completed_at=null where id=?", collaboration);
    assertThat(M4ReviewHistory.select(jdbc, brandProfile(), target, asOf)).isEmpty();
  }

  void times(LocalDateTime review, LocalDateTime completion) {
    jdbc.update("update creator_review set reviewed_at=? where collaboration_id=?", Timestamp.valueOf(review), collaboration);
    jdbc.update("update collaboration set completed_at=? where id=?", Timestamp.valueOf(completion), collaboration);
  }

  @Test void realCompletionHistoryFeedsLiveRecommendationAndPersistedPolicyWithoutChangingOldRun() throws Exception {
    completeFirst();
    UUID profile = brandProfile();
    finishAnother(profile);
    finishAnother(profile);
    LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
    jdbc.update("update creator_review set reviewed_at=? where reviewed_by_user_id=?", Timestamp.valueOf(yesterday), brand.getId());
    jdbc.update("update collaboration set completed_at=? where campaign_id in (select id from campaign where brand_profile_id=?)",
        Timestamp.valueOf(yesterday), profile);

    String vector = "[" + String.join(",", Collections.nCopies(768,"0.1")) + "]";
    jdbc.update("update creator set embedding=?::vector where id=?", vector, channel);
    jdbc.update("insert into public_creator_metric(id,creator_id,followers,avg_views,avg_likes,engagement_rate,niche,"
        + "creator_type,collected_at,freshness_status,data_confidence) values(?,?,25000,5000,200,0.04,'Beauty','KOC',?,'FRESH',0.9)",
        UUID.randomUUID(), channel, Timestamp.valueOf(yesterday.minusDays(1)));
    UUID target = createCampaign(brand, profile);
    jdbc.update("update campaign set niches=ARRAY['Beauty'] where id=?", target);
    long campaignRevision = jdbc.queryForObject("select campaign_revision from campaign where id=?", Long.class, target);
    call(brand, patch("/campaigns/" + target + "/status").header("If-Match", "\"campaign:" + target + ":r" + campaignRevision + "\""),
        Map.of("status", "READY_FOR_DISCOVERY"), 200);
    UUID context = jdbc.queryForObject("select embedding_source_context_id from campaign_context where campaign_id=? and active", UUID.class, target);
    UUID job = jdbc.queryForObject("select id from campaign_embedding_job where context_id=?", UUID.class, context);
    var lease = embeddings.claim(job);
    assertThat(lease).isNotNull();
    embeddings.finish(lease, Collections.nCopies(768, 0.1f));
    jdbc.update("insert into shortlist_item(id,campaign_id,creator_id,user_id,priority,added_at,status,source)"
        + " values(?,?,?,?,'MEDIUM',now(),'SAVED','MANUAL_DISCOVERY')", UUID.randomUUID(), target, channel, brand.getId());

    // Explanation failures fall back to the deterministic explanation; no real AI network is used.
    when(externalAi.getExplanation(any())).thenThrow(new IllegalStateException("External explanation intentionally offline"));
    var first = request(brand, "/campaigns/" + target + "/ai-recommendations", Map.of(), 200, "temporal-m4-first");
    var item = candidate(first);
    assertThat(item.path("scoreBreakdown").path("m4Source").asText()).isEqualTo("DIRECT_BRAND_CREATOR");
    assertThat(item.path("scoreBreakdown").path("m4LearnedScore").asDouble()).isZero(); // Three real 1-star fit reviews.
    var metadata = first.path("runMetadata");
    assertThat(metadata.path("m4HistorySelectionPolicyVersion").asText()).isEqualTo(M4ReviewHistory.POLICY);
    assertThat(metadata.path("m4ReviewEvidenceCount").asInt()).isEqualTo(3);
    assertThat(metadata.path("m4HistoryCutoff").asText()).isEqualTo(metadata.path("historyCutoff").asText());
    UUID run = UUID.fromString(first.path("recommendationRunId").asText());
    String before = jdbc.queryForObject("select scoring_snapshot::text from recommendation_run where id=?", String.class, run);
    // Make one observation future-dated: a new run must no longer have 3 eligible direct reviews.
    jdbc.update("update creator_review set reviewed_at=? where id=?", Timestamp.valueOf(LocalDateTime.now().plusDays(1)), reviewId());
    var second = request(brand, "/campaigns/" + target + "/ai-recommendations", Map.of(), 200, "temporal-m4-second");
    assertThat(candidate(second).path("scoreBreakdown").path("m4Source").asText()).isEqualTo("EXPLICIT_FALLBACK");
    assertThat(jdbc.queryForObject("select scoring_snapshot::text from recommendation_run where id=?", String.class, run)).isEqualTo(before);
  }

  JsonNode candidate(JsonNode result) {
    for (String pool : List.of("recommendations", "provisionalRecommendations"))
      for (JsonNode item : result.path(pool)) if (channel.toString().equals(item.path("creatorId").asText())) return item;
    throw new AssertionError("Real shortlisted creator missing from results: " + result);
  }

  void finishAnother(UUID profile) throws Exception {
    UUID other = createCampaign(brand, profile);
    jdbc.update("update campaign set status='ACTIVE',total_budget=1000000,currency='VND' where id=?", other);
    evaluatedShortlist(brand, other, List.of(channel));
    String relation = call(brand, post("/campaigns/" + other + "/relationships"), Map.of("creatorId",channel),200).path("data").path("id").asText();
    String path = "/campaigns/" + other + "/deals";
    String d = request(brand,path,Map.of("relationshipIds",List.of(relation)),200).path("id").asText();
    path += "/" + d;
    request(brand,path+"/proposals",Map.of("expectedRevision",1,"fee",10000,"currency","VND","terms","Agreed",
        "deliverables",List.of(Map.of("creatorId",channel,"contentType","Video","quantity",1,"dueAt","2027-01-01T00:00:00Z",
            "acceptanceChecklist",List.of("Brand safe"),"maxRevisionRounds",1))),200);
    String collab = request(brand,path+"/confirm",Map.of("expectedRevision",2,"agreementSource","EXTERNAL",
        "agreedAt",Instant.now().minusSeconds(60).toString(),"evidenceSource","EMAIL","evidenceUrl","https://evidence.test/consent",
        "evidenceDescription","Recipient agreed"),200).path("collaborationId").asText();
    request(brand,path+"/start",Map.of("expectedRevision",3),200);
    String exec = "/collaborations/" + collab;
    var state = call(brand,get(exec+"/deliverables"),null,200).path("data");
    String u = state.path("deliverables").get(0).path("id").asText();
    request(owner,exec+"/deliverables/"+u+"/submissions",Map.of("expectedRevision",1,"url","https://content.test/draft","source","CREATOR_PORTAL"),200);
    request(brand,exec+"/deliverables/"+u+"/review",Map.of("expectedRevision",2,"submissionRevision",1,"decision","APPROVE","completedChecklist",List.of("Brand safe")),200);
    state = call(brand,get(exec+"/deliverables"),null,200).path("data");
    state = request(owner,exec+"/deliverables/"+u+"/outcome",Map.of("expectedRevision",state.path("executionRevision").asLong(),"submissionRevision",1,
        "url","https://www.tiktok.com/@test/video/123","periodStart","2026-11-01T00:00:00Z","periodEnd","2026-11-02T00:00:00Z",
        "views",1000,"likes",80,"comments",10,"shares",10,"unavailableMetrics",List.of("clicks")),200);
    state = request(brand,exec+"/outcome/finance",Map.of("expectedRevision",state.path("executionRevision").asLong(),
        "costs",List.of(Map.of("description","Production","amount",1000)),"currency","VND","financialConfirmed",true),200);
    state = request(brand,exec+"/outcome/accept",Map.of("expectedRevision",state.path("executionRevision").asLong(),"outcomeRevision",2),200);
    request(brand,exec+"/review",Map.of("expectedRevision",state.path("executionRevision").asLong(),"outcomeRevision",2,
        "overallRating",2,"brandFitRating",1,"wouldCollaborateAgain",false,"reviewTags",List.of()),200);
  }
}
