package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.learning.*;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.*;

class LearningPostgresIntegrationTest extends ExecutionScenarioSupport {
  @Autowired LearningStore learning;
  @SpyBean LearningEvidenceWriter writer;
  @SpyBean Clock clock;
  @MockBean com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository candidates;
  @MockBean com.influencermatch.backend.creator.client.AiServiceClient ai;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore m3;

  UUID master() {
    return jdbc.queryForObject(
        "select master_id from collaboration where id=?", UUID.class, collaboration);
  }

  UUID brandId() {
    return jdbc.queryForObject(
        "select brand_profile_id from campaign where id=?", UUID.class, campaign);
  }

  LearningPolicy.Snapshot snapshot(UUID b, UUID m) {
    return new org.springframework.transaction.support.TransactionTemplate(transactions)
        .execute(s -> learning.snapshot(b, List.of(m), List.of(channel)));
  }

  void complete() throws Exception {
    ready();
    accept();
    request(brand, root + "/review", review(), 200);
  }

  JsonNode proof() throws Exception {
    return json.readTree(
        jdbc.queryForObject(
            "select learning_snapshot::text from historical_campaign where collaboration_id=?",
            String.class,
            collaboration));
  }

  @Test
  void finalReviewSnapshotsSourcesAndReplayDoesNotDuplicate() throws Exception {
    ready();
    accept();
    String key = UUID.randomUUID().toString();
    var body = review();
    request(brand, root + "/review", body, 200, key);
    request(brand, root + "/review", body, 200, key);
    var proof = proof();
    assertThat(proof.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(proof.path("onTime").asBoolean()).isTrue();
    assertThat(proof.path("kpi").isNull()).isTrue();
    assertThat(proof.path("metricSource").asText()).isEqualTo("SELF_REPORTED");
    assertThat(proof.path("acceptanceId").asText()).isNotBlank();
    assertThat(snapshot(brandId(), master()).evidence()).hasSize(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from historical_campaign where collaboration_id=?", collaboration))
        .hasMessageContaining("immutable");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from historical_campaign where collaboration_id=?",
                Integer.class,
                collaboration))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update historical_campaign set learning_snapshot='{}' where"
                        + " collaboration_id=?",
                    collaboration))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update collaboration set learning_context='{}' where id=?", collaboration))
        .hasMessageContaining("immutable");
  }

  @Test
  void evidenceFailureAfterWriteRollsBackCompletionReviewAndReceipt() throws Exception {
    ready();
    accept();
    doAnswer(
            i -> {
              i.callRealMethod();
              throw new IllegalStateException("simulated learning persistence failure");
            })
        .when(writer)
        .review(any(), any());
    String key = UUID.randomUUID().toString();
    var body = review();
    request(brand, root + "/review", body, 500, key);
    assertThat(state().path("status").asText()).isEqualTo("AWAITING_REVIEW");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_review where collaboration_id=?",
                Integer.class,
                collaboration))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from historical_campaign where collaboration_id=?",
                Integer.class,
                collaboration))
        .isZero();
    doCallRealMethod().when(writer).review(any(), any());
    request(brand, root + "/review", body, 200, key);
  }

  @Test
  void brandExternalSubmissionDoesNotBecomeLateObservation() throws Exception {
    request(
        brand,
        root + "/deliverables/" + unit() + "/submissions",
        Map.of(
            "expectedRevision",
            dRevision,
            "url",
            "https://proof.test/draft",
            "source",
            "BRAND_EXTERNAL",
            "sourceDescription",
            "Received externally"),
        200);
    dRevision++;
    approve();
    publish(false);
    outcome();
    accept();
    request(brand, root + "/review", review(), 200);
    assertThat(proof().path("onTime").isNull()).isTrue();
  }

  @Test
  void ownerOrPlatformChangedAfterAgreementDoesNotRewriteHistoricalIdentity() throws Exception {
    UUID original = master();
    ready();
    var next =
        masters.saveAndFlush(
            com.influencermatch.backend.creator.model.CreatorMaster.builder()
                .user(account(Role.CREATOR))
                .canonicalName("Next owner")
                .build());
    jdbc.update(
        "update creator set master_id=?,platform='INSTAGRAM' where id=?", next.getId(), channel);
    accept();
    request(brand, root + "/review", review(), 200);
    assertThat(proof().path("platform").asText()).isEqualTo("tiktok");
    var s = snapshot(brandId(), original);
    assertThat(s.evidence()).hasSize(1);
    assertThat(s.evidence().getFirst().masterId()).isEqualTo(original);
    assertThat(s.m1(next.getId(), channel, "AWARENESS").review().count()).isZero();
  }

  @Test
  void concurrentFinalReviewSameKeyProducesOneEvidence() throws Exception {
    ready();
    accept();
    var body = review();
    String key = UUID.randomUUID().toString();
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var tasks = new ArrayList<Future<JsonNode>>();
      for (int i = 0; i < 2; i++)
        tasks.add(
            pool.submit(
                () -> {
                  start.await();
                  return request(brand, root + "/review", body, 200, key);
                }));
      start.countDown();
      for (var f : tasks) f.get(30, TimeUnit.SECONDS);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from historical_campaign where collaboration_id=?",
                  Integer.class,
                  collaboration))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  // Actual proposal/confirm workflow, not SQL-fabricated acceptance or KPI targets.
  void newFlow(User b, List<LearningKpis.Target> targets) throws Exception {
    brand = b;
    var existing = jdbc.queryForList("select id from brand_profile where user_id=?", brand.getId());
    campaign =
        createCampaign(
            brand, existing.isEmpty() ? createBrand(brand) : (UUID) existing.getFirst().get("id"));
    jdbc.update(
        "update campaign set"
            + " status='ACTIVE',total_budget=1000000,currency='VND',creator_mode='SINGLE',target_creator_count=1"
            + " where id=?",
        campaign);
    var rel =
        call(
                brand,
                post("/campaigns/" + campaign + "/relationships"),
                Map.of("creatorId", channel),
                200)
            .path("data")
            .path("id")
            .asText();
    deal =
        UUID.fromString(
            call(
                    brand,
                    post("/campaigns/" + campaign + "/deals")
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                    Map.of("relationshipIds", List.of(rel)),
                    200)
                .path("data")
                .path("id")
                .asText());
    request(
        brand,
        "/campaigns/" + campaign + "/deals/" + deal + "/proposals",
        Map.of(
            "expectedRevision",
            1,
            "fee",
            1000,
            "currency",
            "VND",
            "terms",
            "Learning terms",
            "deliverables",
            List.of(
                Map.of(
                    "creatorId",
                    channel,
                    "contentType",
                    "Video",
                    "quantity",
                    1,
                    "dueAt",
                    "2027-01-01T00:00:00Z",
                    "acceptanceChecklist",
                    List.of("Brand safe"),
                    "maxRevisionRounds",
                    1)),
            "kpiTargets",
            targets),
        200);
    var c =
        request(
            brand,
            "/campaigns/" + campaign + "/deals/" + deal + "/confirm",
            Map.of(
                "expectedRevision",
                2,
                "agreementSource",
                "EXTERNAL",
                "agreedAt",
                Instant.now().minusSeconds(60).toString(),
                "evidenceSource",
                "EMAIL",
                "evidenceUrl",
                "https://proof.test/agreement",
                "evidenceDescription",
                "Agreed"),
            200);
    collaboration = UUID.fromString(c.path("collaborationId").asText());
    root = "/collaborations/" + collaboration;
    dRevision = 1;
    request(
        brand,
        "/campaigns/" + campaign + "/deals/" + deal + "/start",
        Map.of("expectedRevision", 3),
        200);
  }

  @Test
  void kpiConfirmedBeforeMeasurementIsEvaluatedFromAcceptedRevision() throws Exception {
    Instant initial = clock.instant();
    Instant start = initial.plusSeconds(10), end = initial.plusSeconds(20);
    newFlow(
        account(Role.BRAND),
        List.of(new LearningKpis.Target("views", channel, "VIEWS", 2000L, start, end)));
    when(clock.instant()).thenReturn(initial.plusSeconds(60));
    submit();
    approve();
    publish(false);
    var measurement = new HashMap<String, Object>(measurement());
    measurement.put("periodStart", start.toString());
    measurement.put("periodEnd", end.toString());
    request(
        brand,
        root + "/outcome",
        Map.of(
            "expectedRevision",
            revision(),
            "reason",
            "Actual measured period",
            "posts",
            List.of(measurement),
            "costs",
            List.of(Map.of("description", "Production", "amount", 1000)),
            "currency",
            "VND",
            "financialConfirmed",
            true),
        200);
    accept();
    request(brand, root + "/review", review(), 200);
    assertThat(proof().path("kpi").asDouble()).isEqualTo(.5);
    assertThat(proof().path("metricSource").asText()).isEqualTo("SELF_REPORTED");
    assertThat(proof().path("agreement").path("kpiTargets").size()).isEqualTo(1);
  }

  @Test
  void acceptedOldAgreementWithoutSnapshotDoesNotBecomeTrustedLearning() throws Exception {
    // An old agreement has no learning_context. Preserve its original execution/history only.
    UUID older = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " collaboration(id,relationship_id,campaign_id,creator_id,status,execution_source,updated_at)"
            + " select ?,relationship_id,campaign_id,creator_id,'CANCELLED','LEGACY',now() from"
            + " collaboration where id=?",
        older,
        collaboration);
    assertThat(
            jdbc.queryForObject(
                "select learning_context from collaboration where id=?", String.class, older))
        .isNull();
    assertThat(snapshot(brandId(), master()).evidence()).isEmpty();
  }

  @Test
  void v29AcceptedReviewCanSupplyRatingsWithoutInventingMissingSnapshotFields() throws Exception {
    doNothing()
        .when(writer)
        .review(any(), any()); // models pre-V33 completion; the saved V29 source rows are real
    complete();
    var s = snapshot(brandId(), master());
    assertThat(s.evidence()).hasSize(1);
    var e = s.evidence().getFirst();
    assertThat(e.rating()).isEqualTo(2);
    assertThat(e.fitRating()).isEqualTo(1);
    assertThat(e.onTime()).isNull();
    assertThat(e.kpi()).isNull();
    assertThat(e.platform()).isNull();
    assertThat(e.niche()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select learning_snapshot from historical_campaign where collaboration_id=?",
                String.class,
                collaboration))
        .isNull();
  }

  @Test
  void recommendationUsesCrossBrandAggregatesButNeverExposesSourcesAndAiFailureKeepsRanking()
      throws Exception {
    User first = brand;
    UUID m = master();
    complete();
    newFlow(account(Role.BRAND), List.of());
    complete();
    // Re-use the first Brand profile for a new campaign by using a third distinct Brand here;
    // M1 requires two Brands; M4 still cannot see any other Brand's ratings.
    newFlow(account(Role.BRAND), List.of());
    complete();
    var snapshot = snapshot(brandId(), m);
    var m1 = snapshot.m1(m, channel, "AWARENESS");
    assertThat(m1.score()).isNotNull();
    assertThat(m1.review().brandCount()).isEqualTo(3);
    assertThat(snapshot.m4(brandId(), m, "tiktok", "skin")).isNull();
    var c = mock(RecommendationCandidateProjection.class);
    when(c.getCreatorId()).thenReturn(channel);
    when(c.getMasterId()).thenReturn(m);
    when(c.getPlatform()).thenReturn("TIKTOK");
    when(c.getFollowers()).thenReturn(25000L);
    when(c.getSemanticScore()).thenReturn(.7);
    when(candidates.findCandidatesByShortlist(any(), anyString(), anyBoolean(), anyCollection()))
        .thenReturn(List.of(c));
    when(ai.getExplanation(any())).thenThrow(new IllegalStateException("Unavailable explanation"));
    call(brand, post("/campaigns/" + campaign + "/ai-recommendations"), null, 409);
    UUID job =
        jdbc.queryForObject(
            "select j.id from campaign_embedding_job j join campaign_context c on c.id=j.context_id"
                + " where c.campaign_id=? and c.active",
            UUID.class,
            campaign);
    m3.finish(m3.claim(job), Collections.nCopies(768, .1f));
    var result =
        call(brand, post("/campaigns/" + campaign + "/ai-recommendations"), null, 200).path("data");
    String publicJson = result.toString();
    var detail = result.path("recommendations").get(0).path("scoreBreakdown");
    assertThat(detail.path("m1Available").asBoolean()).isTrue();
    assertThat(detail.path("m4Available").asBoolean()).isFalse();
    assertThat(detail.path("m1Details").path("review").path("brandCount").asInt()).isEqualTo(3);
    for (var e : snapshot.evidence())
      assertThat(publicJson).doesNotContain(e.id().toString(), e.reviewId().toString());
    UUID run = UUID.fromString(result.path("recommendationRunId").asText());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from recommendation_scoring_evidence where run_id=?",
                Integer.class,
                run))
        .isEqualTo(6);
    var runs =
        call(brand, get("/campaigns/" + campaign + "/ai-recommendations/runs"), null, 200)
            .path("data");
    for (var e : snapshot.evidence())
      assertThat(runs.toString()).doesNotContain(e.id().toString(), e.reviewId().toString());
    assertThat(runs.get(0).path("scoringPolicyVersion").asText()).isEqualTo(LearningPolicy.VERSION);
    call(first, get("/campaigns/" + campaign + "/ai-recommendations/runs"), null, 404);
    var capture =
        org.mockito.ArgumentCaptor.forClass(
            com.influencermatch.backend.recommendation.dto.ExplainRequestDto.class);
    verify(ai).getExplanation(capture.capture());
    String aiInput = json.writeValueAsString(capture.getValue());
    for (var e : snapshot.evidence())
      assertThat(aiInput).doesNotContain(e.id().toString(), e.reviewId().toString());
    assertThat(publicJson).doesNotContain("rankingScore", "reviewEvidence", "timeEvidence");
    var storedParameters =
        json.readTree(
            jdbc.queryForObject(
                "select scoring_policy_parameters::text from recommendation_run where id=?",
                String.class,
                run));
    assertThat(storedParameters.path("candidateInputs").size()).isEqualTo(1);
    assertThat(
            storedParameters.path("candidateInputs").get(0).path("unroundedFinalScore").isNumber())
        .isTrue();
    assertThat(runs.toString()).doesNotContain("candidateInputs", "unroundedFinalScore");
    String originalRun =
        jdbc.queryForObject(
            "select scoring_snapshot::text from recommendation_run where id=?", String.class, run);
    User current = brand;
    newFlow(current, List.of());
    complete();
    newFlow(current, List.of());
    complete();
    assertThat(snapshot(brandId(), m).m4(brandId(), m, "tiktok", "other").count()).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select scoring_snapshot::text from recommendation_run where id=?",
                String.class,
                run))
        .isEqualTo(originalRun);
  }

  @Test
  void personalM4IsBayesianAndRebuildingDoesNotIncrementCounts() throws Exception {
    User b = brand;
    UUID m = master();
    complete();
    newFlow(b, List.of());
    complete();
    newFlow(b, List.of());
    complete();
    var s = snapshot(brandId(), m);
    var cohort = s.m4(brandId(), m, "instagram", "other");
    assertThat(cohort.scope()).isEqualTo("BRAND_MASTER");
    assertThat(cohort.score()).isEqualTo(20);
    assertThat(s.m1(m, channel, "AWARENESS").score()).isNull(); // one Brand is not general history
    String before =
        jdbc.queryForObject(
            "select pattern_value::text from brand_pattern where brand_profile_id=? and"
                + " pattern_type=? and cohort_key=?",
            String.class,
            brandId(),
            LearningPolicy.M4_TYPE,
            "MASTER:" + m);
    snapshot(brandId(), m);
    assertThat(
            jdbc.queryForObject(
                "select pattern_value::text from brand_pattern where brand_profile_id=? and"
                    + " pattern_type=? and cohort_key=?",
                String.class,
                brandId(),
                LearningPolicy.M4_TYPE,
                "MASTER:" + m))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select evidence_count from brand_pattern where brand_profile_id=? and"
                    + " pattern_type=? and cohort_key=?",
                Integer.class,
                brandId(),
                LearningPolicy.M4_TYPE,
                "MASTER:" + m))
        .isEqualTo(3);
  }

  @Test
  void v32ToV33PreservesExecutionEvidenceAndDoesNotInventLearningContext() throws Exception {
    complete();
    String schema = "learning_" + UUID.randomUUID().toString().replace("-", "");
    var flyway =
        org.flywaydb.core.Flyway.configure()
            .dataSource(jdbc.getDataSource())
            .schemas(schema)
            .defaultSchema(schema)
            .target("32")
            .load();
    try {
      flyway.migrate();
      for (String table :
          List.of(
              "user",
              "brand_profile",
              "creator_master",
              "creator",
              "campaign",
              "relationship",
              "collaboration",
              "cooperation_deal",
              "cooperation_proposal",
              "cooperation_member",
              "collaboration_channel",
              "collaboration_deliverable",
              "collaboration_submission",
              "collaboration_content_review",
              "collaboration_publication",
              "campaign_outcome",
              "collaboration_outcome_revision",
              "collaboration_outcome_acceptance",
              "creator_review",
              "historical_campaign")) {
        String columns =
            String.join(
                ",",
                jdbc
                    .queryForList(
                        "select column_name from information_schema.columns where table_schema=?"
                            + " and table_name=? order by ordinal_position",
                        String.class,
                        schema,
                        table)
                    .stream()
                    .map(v -> "\"" + v + "\"")
                    .toList());
        jdbc.execute(
            "insert into "
                + schema
                + ".\""
                + table
                + "\" ("
                + columns
                + ") select "
                + columns
                + " from public.\""
                + table
                + "\" on conflict do nothing");
      }
      String original =
          jdbc.queryForObject(
              "select to_jsonb(h)::text from "
                  + schema
                  + ".historical_campaign h where collaboration_id=?",
              String.class,
              collaboration);
      assertThat(
              org.flywaydb.core.Flyway.configure()
                  .dataSource(jdbc.getDataSource())
                  .schemas(schema)
                  .defaultSchema(schema)
                  .target("33")
                  .load()
                  .migrate()
                  .migrationsExecuted)
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select (to_jsonb(h)-'learning_snapshot')::text from "
                      + schema
                      + ".historical_campaign h where collaboration_id=?",
                  String.class,
                  collaboration))
          .isEqualTo(original);
      assertThat(
              jdbc.queryForObject(
                  "select learning_context from " + schema + ".collaboration where id=?",
                  String.class,
                  collaboration))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "select learning_snapshot from "
                      + schema
                      + ".historical_campaign where collaboration_id=?",
                  String.class,
                  collaboration))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + schema + ".recommendation_scoring_evidence",
                  Integer.class))
          .isZero();
    } finally {
      jdbc.execute("drop schema " + schema + " cascade");
    }
  }
}
