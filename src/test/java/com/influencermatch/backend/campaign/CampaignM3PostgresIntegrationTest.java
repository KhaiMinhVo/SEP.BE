package com.influencermatch.backend.campaign;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.campaign.embedding.*;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.recommendation.dto.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class CampaignM3PostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired CampaignEmbeddingStore store;
  @Autowired CampaignEmbeddingWorker worker;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;
  @MockBean AiServiceClient ai;
  @MockBean PublicCreatorMetricRepository metrics;
  @MockBean Clock clock;
  Instant now = Instant.parse("2026-11-08T00:00:00Z");
  User owner;
  UUID brand, campaign;

  @BeforeEach
  void setup() throws Exception {
    when(clock.instant()).thenAnswer(i -> now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    owner = account(Role.BRAND);
    brand = createBrand(owner);
    campaign = createCampaign(owner, brand);
    when(ai.getBrandEmbedding(anyString(), anyString())).thenReturn(Collections.nCopies(768, 0.1f));
    when(ai.getExplanation(any()))
        .thenReturn(
            ExplainResponseDto.builder()
                .summary("Fixture")
                .reasons(List.of())
                .explanationAvailable(false)
                .build());
  }

  String etag(UUID id) throws Exception {
    return mvc.perform(
            get("/campaigns/" + id).header("Authorization", "Bearer " + jwt.generateToken(owner)))
        .andReturn()
        .getResponse()
        .getHeader("ETag");
  }

  UUID source() {
    return jdbc.queryForObject(
        "select embedding_source_context_id from campaign_context where campaign_id=? and active",
        UUID.class,
        campaign);
  }

  UUID job() {
    return jdbc.queryForObject(
        "select id from campaign_embedding_job where context_id=?", UUID.class, source());
  }

  void ready() throws Exception {
    call(
        owner,
        patch("/campaigns/" + campaign + "/status"),
        Map.of("status", "READY_FOR_DISCOVERY"),
        200);
  }

  int raw(MockHttpServletRequestBuilder request, Object body) throws Exception {
    request.header("Authorization", "Bearer " + jwt.generateToken(owner));
    if (body != null)
      request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
    return mvc.perform(request).andReturn().getResponse().getStatus();
  }

  long used() {
    return jdbc.queryForObject(
        "select sum(used_ai_quota) from subscription where brand_profile_id=?", Long.class, brand);
  }

  void candidates() {
    var candidate = mock(RecommendationCandidateProjection.class);
    when(candidate.getCreatorId()).thenReturn(shortlistedCreator(campaign, owner));
    when(candidate.getPlatform()).thenReturn("TIKTOK");
    when(candidate.getFollowers()).thenReturn(20000L);
    when(candidate.getNiche()).thenReturn("Beauty");
    when(metrics.findCandidatesByShortlist(any(), anyString(), anyBoolean(), anyCollection()))
        .thenReturn(List.of(candidate));
    when(metrics.findTopCandidatesByEmbedding(
            anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(candidate));
  }

  @Test
  void partialDraftAndReadyValidationAreSeparate() throws Exception {
    var id =
        UUID.fromString(
            call(owner, post("/brands/" + brand + "/campaigns"), Map.of("name", "Partial"), 201)
                .path("data")
                .path("id")
                .asText());
    var failed =
        call(
            owner,
            patch("/campaigns/" + id + "/status"),
            Map.of("status", "READY_FOR_DISCOVERY"),
            400);
    assertThat(failed.path("fieldErrors").has("brief")).isTrue();
    assertThat(jdbc.queryForObject("select status from campaign where id=?", String.class, id))
        .isEqualTo("DRAFT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from campaign_context where campaign_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void explicitlyNullLegacyBudgetIsRejectedAndOldAmountsRemainOnPut() throws Exception {
    var node = json.createObjectNode().put("name", "Legacy request").putNull("budgetMin");
    assertThat(raw(post("/brands/" + brand + "/campaigns"), node)).isEqualTo(400);
    jdbc.update("update campaign set budget_min=123,budget_max=456 where id=?", campaign);
    call(owner, put("/campaigns/" + campaign), campaignBody(), 200);
    assertThat(
            jdbc.queryForObject(
                "select budget_max from campaign where id=?", Integer.class, campaign))
        .isEqualTo(456);
  }

  @Test
  void budgetFeeAndDeliverableValidationNeverCreatesUnexpectedData() throws Exception {
    for (String field : List.of("legacy", "mode", "platform", "quantity", "currency", "fee")) {
      var body = campaignBody();
      switch (field) {
        case "legacy" -> body.put("budgetMin", 100);
        case "mode" -> body.put("targetCreatorCount", 2);
        case "platform" ->
            body.put(
                "deliverables",
                List.of(Map.of("platform", "INSTAGRAM", "contentType", "Review", "quantity", 1)));
        case "quantity" ->
            body.put(
                "deliverables",
                List.of(Map.of("platform", "TIKTOK", "contentType", "Review", "quantity", 0)));
        case "currency" -> body.put("currency", "USD");
        case "fee" -> {
          body.put("creatorFeeMin", 200);
          body.put("creatorFeeMax", 100);
        }
      }
      call(owner, post("/brands/" + brand + "/campaigns"), body, 400);
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from campaign where brand_profile_id=?", Integer.class, brand))
        .isEqualTo(1);
    var body = campaignBody();
    body.put("creatorMode", "MULTI");
    body.put("targetCreatorCount", 3);
    body.put("creatorFeeMin", 100);
    body.put("creatorFeeMax", 200);
    var response = call(owner, put("/campaigns/" + campaign), body, 200).path("data");
    assertThat(response.path("totalBudget").asInt()).isEqualTo(1000000);
    assertThat(response.path("creatorFeeMax").asInt()).isEqualTo(200);
    assertThat(response.path("feeAssessmentStatus").asText()).isEqualTo("UNAVAILABLE");
  }

  @Test
  void revisionsNoOpAndFrozenBrief() throws Exception {
    var tag = etag(campaign);
    assertThat(raw(put("/campaigns/" + campaign), campaignBody())).isEqualTo(428);
    assertThat(raw(put("/campaigns/" + campaign).header("If-Match", "wrong"), campaignBody()))
        .isEqualTo(400);
    assertThat(raw(put("/campaigns/" + campaign).header("If-Match", tag), campaignBody()))
        .isEqualTo(200);
    assertThat(etag(campaign)).isEqualTo(tag);
    var body = campaignBody();
    body.put("brief", "Changed");
    assertThat(raw(put("/campaigns/" + campaign).header("If-Match", tag), body)).isEqualTo(200);
    assertThat(raw(put("/campaigns/" + campaign).header("If-Match", tag), body)).isEqualTo(409);
    ready();
    assertThat(raw(put("/campaigns/" + campaign).header("If-Match", etag(campaign)), body))
        .isEqualTo(409);
  }

  @Test
  void concurrentRevisionAllowsOnlyOneWriter() throws Exception {
    var tag = etag(campaign);
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        int n = i;
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  var body = campaignBody();
                  body.put("name", "Concurrent " + n);
                  return raw(put("/campaigns/" + campaign).header("If-Match", tag), body);
                }));
      }
      gate.countDown();
      assertThat(
              List.of(
                  futures.get(0).get(20, TimeUnit.SECONDS),
                  futures.get(1).get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void readyCreatesFrozenContextAndJobInOneTransaction() throws Exception {
    ready();
    assertThat(source()).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "select status from campaign_embedding_job where id=?", String.class, job()))
        .isEqualTo("PENDING");
    var snapshot =
        jdbc.queryForObject(
            "select context_data::text from campaign_context where id=?", String.class, source());
    assertThat(json.readTree(snapshot).path("deliverables").get(0).path("quantity").asInt())
        .isEqualTo(1);
    assertThatThrownBy(
            () -> jdbc.update("update campaign_context set context_data='{}' where id=?", source()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    call(owner, patch("/brands/me"), Map.of("guidelines", "Later Brand guideline"), 200);
    assertThat(
            jdbc.queryForObject(
                "select context_data::text from campaign_context where id=?",
                String.class,
                source()))
        .isEqualTo(snapshot);
  }

  @Test
  void pendingAndEmptySourcesNeverReserveQuotaOrCallEmbedding() throws Exception {
    ready();
    var missing = call(owner, post("/campaigns/" + campaign + "/ai-recommendations"), null, 409);
    assertThat(missing.path("code").asText()).isEqualTo("RECOMMENDATION_NO_CANDIDATES");
    candidates();
    var pending = call(owner, post("/campaigns/" + campaign + "/ai-recommendations"), null, 409);
    assertThat(pending.path("embeddingStatus").asText()).isEqualTo("PENDING");
    assertThat(used()).isZero();
    verify(ai, never()).getEmbedding(anyString());
    worker.process(job());
    clearInvocations(ai);
    when(metrics.findCandidatesByShortlist(any(), anyString(), anyBoolean(), anyCollection()))
        .thenReturn(List.of());
    var empty = call(owner, post("/campaigns/" + campaign + "/ai-recommendations"), null, 409);
    assertThat(empty.path("code").asText()).isEqualTo("RECOMMENDATION_NO_CANDIDATES");
    assertThat(used()).isZero();
    verify(metrics, never())
        .findTopCandidatesByEmbedding(
            anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt());
    verify(ai, never()).getBrandEmbedding(anyString(), anyString());
  }

  @Test
  void shortlistScoringPersistsProvenanceAndRejectsDiscoverySource() throws Exception {
    ready();
    worker.process(job());
    candidates();
    var first =
        call(owner, post("/campaigns/" + campaign + "/ai-recommendations"), null, 200).path("data");
    UUID run = UUID.fromString(first.path("recommendationRunId").asText());
    UUID original = source();
    String prior =
        jdbc.queryForObject(
            "select scoring_snapshot::text from recommendation_run where id=?", String.class, run);
    assertThat(
            jdbc.queryForObject(
                "select candidate_source from recommendation_run where id=?", String.class, run))
        .isEqualTo("SHORTLIST");
    call(
        owner,
        post("/campaigns/" + campaign + "/ai-recommendations/feedback"),
        Map.of(
            "creatorId", UUID.randomUUID(), "action", "REJECT", "reasonNote", "private feedback"),
        200);
    assertThat(source()).isEqualTo(original);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from campaign_embedding_job where context_id=?",
                Integer.class,
                original))
        .isEqualTo(1);
    call(
        owner,
        post("/campaigns/" + campaign + "/ai-recommendations?candidateSource=DISCOVERY"),
        null,
        400);
    verify(metrics, never())
        .findTopCandidatesByEmbedding(
            anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt());
    assertThat(
            jdbc.queryForObject(
                "select scoring_snapshot::text from recommendation_run where id=?",
                String.class,
                run))
        .isEqualTo(prior);
    assertThat(
            jdbc.queryForObject(
                "select context_data->>'embeddingText' from campaign_context where id=?",
                String.class,
                original))
        .doesNotContain("private feedback");
  }

  @Test
  void staleWorkerAndRetryScheduleDoNotOverwriteResults() throws Exception {
    ready();
    var first = store.claim(job());
    assertThat(store.claim(job())).isNull();
    now = now.plusSeconds(121);
    var next = store.claim(job());
    assertThat(store.finish(first, Collections.nCopies(768, 0.2f))).isFalse();
    assertThat(store.finish(next, Collections.nCopies(768, 0.1f))).isTrue();
    assertThat(store.view(source()).status()).isEqualTo("READY");
  }

  @Test
  void providerFailureLeavesCampaignAndSupportsBoundedRetry() throws Exception {
    ready();
    var tag = etag(campaign);
    when(ai.getBrandEmbedding(anyString(), anyString()))
        .thenAnswer(
            i -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              throw new IllegalStateException("timeout");
            });
    for (int attempt = 1; attempt <= 5; attempt++) {
      worker.process(job());
      assertThat(store.view(source()).status()).isEqualTo(attempt == 5 ? "FAILED" : "PENDING");
      if (attempt < 5) now = now.plusSeconds(15L << (attempt - 1));
    }
    assertThat(etag(campaign)).isEqualTo(tag);
    call(owner, post("/campaigns/" + campaign + "/context/embedding/retry"), null, 200);
    assertThat(store.view(source()).status()).isEqualTo("PENDING");
    call(owner, post("/campaigns/" + campaign + "/context/embedding/retry"), null, 409);
  }

  @Test
  void legacyPreparationCommitsOnceWithoutFabricatingMissingFields() throws Exception {
    shortlistedCreator(campaign, owner);
    jdbc.update(
        "update campaign set"
            + " status='ACTIVE',brief=null,total_budget=null,creator_mode=null,target_creator_count=null,budget_min=123"
            + " where id=?",
        campaign);
    for (int n = 0; n < 2; n++)
      call(owner, post("/campaigns/" + campaign + "/ai-recommendations"), null, 409);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from campaign_context where campaign_id=?",
                Integer.class,
                campaign))
        .isEqualTo(1);
    var data =
        json.readTree(
            jdbc.queryForObject(
                "select context_data::text from campaign_context where id=?",
                String.class,
                source()));
    assertThat(data.path("snapshotKind").asText()).isEqualTo("LEGACY_SNAPSHOT");
    assertThat(data.path("budgetMin").asInt()).isEqualTo(123);
    assertThat(data.path("totalBudget").isMissingNode() || data.path("totalBudget").isNull())
        .isTrue();
    assertThat(used()).isZero();
  }

  @Test
  void enqueueFailureRollsBackReadyAndRevision() throws Exception {
    var before = etag(campaign);
    jdbc.execute(
        "CREATE FUNCTION m3_enqueue_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'test enqueue failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER m3_enqueue_failure BEFORE INSERT ON campaign_embedding_job FOR EACH ROW"
            + " EXECUTE FUNCTION m3_enqueue_failure()");
    try {
      call(
          owner,
          patch("/campaigns/" + campaign + "/status"),
          Map.of("status", "READY_FOR_DISCOVERY"),
          500);
      assertThat(etag(campaign)).isEqualTo(before);
      assertThat(
              jdbc.queryForObject("select status from campaign where id=?", String.class, campaign))
          .isEqualTo("DRAFT");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from campaign_context where campaign_id=?",
                  Integer.class,
                  campaign))
          .isZero();
    } finally {
      jdbc.execute("DROP TRIGGER m3_enqueue_failure ON campaign_embedding_job");
      jdbc.execute("DROP FUNCTION m3_enqueue_failure()");
    }
  }

  @Test
  void v26UpgradePreservesLegacyBudgetsContextAndRun() {
    String schema = "m3_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var fly =
        org.flywaydb.core.Flyway.configure()
            .dataSource(jdbc.getDataSource())
            .schemas(schema)
            .defaultSchema(schema);
    fly.target("26").load().migrate();
    UUID u = UUID.randomUUID(),
        b = UUID.randomUUID(),
        c = UUID.randomUUID(),
        context = UUID.randomUUID(),
        run = UUID.randomUUID();
    try {
      jdbc.update(
          "insert into "
              + schema
              + ".\"user\"(id,email,full_name,role,status,created_at,updated_at)"
              + " values(?,?,'Legacy','BRAND','ACTIVE',now(),now())",
          u,
          u + "@example.test");
      jdbc.update(
          "insert into "
              + schema
              + ".brand_profile(id,user_id,business_name,created_at,updated_at)"
              + " values(?,?,'Legacy',now(),now())",
          b,
          u);
      jdbc.update(
          "insert into "
              + schema
              + ".campaign(id,brand_profile_id,name,product_service,objective,budget_min,budget_max,status,created_at,updated_at)"
              + " values(?,?,'Legacy','Product','TRAFFIC',123,456,'ACTIVE',now(),now())",
          c,
          b);
      jdbc.update(
          "insert into "
              + schema
              + ".campaign_context(id,campaign_id,context_version,context_data,active,created_at,updated_at)"
              + " values(?,?,7,'{\"legacy\":true}',true,now(),now())",
          context,
          c);
      jdbc.update(
          "insert into "
              + schema
              + ".recommendation_run(id,campaign_id,brand_profile_id,context_version,status,candidates_count,created_at)"
              + " values(?,?,?,7,'COMPLETED',1,now())",
          run,
          c,
          b);
      assertThat(fly.target("27").load().migrate().migrationsExecuted).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select budget_max from " + schema + ".campaign where id=?", Integer.class, c))
          .isEqualTo(456);
      assertThat(
              jdbc.queryForObject(
                  "select total_budget from " + schema + ".campaign where id=?",
                  java.math.BigDecimal.class,
                  c))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "select embedding_source_context_id from "
                      + schema
                      + ".campaign_context where id=?",
                  UUID.class,
                  context))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "select context_version from " + schema + ".campaign_context where id=?",
                  Integer.class,
                  context))
          .isEqualTo(7);
      assertThat(
              jdbc.queryForObject(
                  "select embedding_job_id from " + schema + ".recommendation_run where id=?",
                  UUID.class,
                  run))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + schema + ".campaign_embedding_job", Integer.class))
          .isZero();
    } finally {
      jdbc.execute("drop schema " + schema + " cascade");
    }
  }

  @Test
  void concurrentFeedbackKeepsOneActiveContextAndOneEmbeddingSource() throws Exception {
    ready();
    UUID src = source();
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var jobs = new ArrayList<Future<Integer>>();
      for (int n = 0; n < 2; n++)
        jobs.add(
            pool.submit(
                () -> {
                  gate.await();
                  return raw(
                      post("/campaigns/" + campaign + "/ai-recommendations/feedback"),
                      Map.of("creatorId", UUID.randomUUID(), "action", "REJECT"));
                }));
      gate.countDown();
      for (var future : jobs) assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(200);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from campaign_context where campaign_id=? and active",
                  Integer.class,
                  campaign))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select max(context_version) from campaign_context where campaign_id=?",
                  Integer.class,
                  campaign))
          .isEqualTo(3);
      assertThat(source()).isEqualTo(src);
      assertThat(
              jdbc.queryForObject(
                  "select jsonb_array_length(context_data->'excludedCreatorIds') from"
                      + " campaign_context where campaign_id=? and active",
                  Integer.class,
                  campaign))
          .isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }
}
