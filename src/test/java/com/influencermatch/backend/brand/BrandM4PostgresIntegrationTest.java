package com.influencermatch.backend.brand;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.brand.embedding.*;
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

class BrandM4PostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean com.influencermatch.backend.creator.client.AiServiceClient ai;
  @MockBean Clock clock;

  @MockBean
  com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository candidateMetrics;

  @Autowired com.influencermatch.backend.brand.service.BrandLearnedPatterns learned;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired BrandEmbeddingStore jobs;
  @Autowired BrandEmbeddingWorker worker;
  User owner;
  Instant now;
  JsonNode initial;
  String tag;

  record Result(JsonNode data, String etag) {}

  @org.springframework.beans.factory.annotation.Autowired
  com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore m3Store;

  @BeforeEach
  void init() throws Exception {
    now = Instant.parse("2026-11-08T00:00:00Z");
    when(clock.instant()).thenAnswer(i -> now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    owner = account(Role.BRAND);
    var r =
        request(
            post("/brands"),
            Map.of(
                "businessName",
                "Acme",
                "phoneNumber",
                "0912345678",
                "industry",
                "Beauty",
                "productCategories",
                List.of("Skin"),
                "targetAudiences",
                List.of("Adults"),
                "brandTone",
                "Friendly",
                "preferredPlatforms",
                List.of("TIKTOK")),
            null,
            201);
    initial = r.data;
    tag = r.etag;
  }

  Result request(MockHttpServletRequestBuilder req, Object body, String match, int expected)
      throws Exception {
    req.header("Authorization", "Bearer " + jwt.generateToken(owner));
    if (match != null) req.header("If-Match", match);
    if (body != null)
      req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
    var r = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse();
    return new Result(
        r.getContentAsString().isEmpty()
            ? json.nullNode()
            : json.readTree(r.getContentAsString()).path("data"),
        r.getHeader("ETag"));
  }

  UUID profile() {
    return UUID.fromString(initial.path("id").asText());
  }

  UUID context() {
    return jdbc.queryForObject(
        "select id from brand_context where brand_profile_id=? order by context_version desc limit"
            + " 1",
        UUID.class,
        profile());
  }

  UUID job() {
    return jdbc.queryForObject(
        "select id from brand_embedding_job where context_id=?", UUID.class, context());
  }

  @Test
  void v25UpgradePreservesExistingContextAndDoesNotInventHistory() {
    String s = "m4_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var config =
        org.flywaydb.core.Flyway.configure()
            .dataSource(jdbc.getDataSource())
            .schemas(s)
            .defaultSchema(s);
    config.target("25").load().migrate();
    UUID user = UUID.randomUUID(), brand = UUID.randomUUID(), ctx = UUID.randomUUID();
    try {
      jdbc.update(
          "insert into "
              + s
              + ".\"user\"(id,email,full_name,role,status,created_at,updated_at)"
              + " values(?,?,'Legacy','BRAND','ACTIVE',now(),now())",
          user,
          user + "@example.test");
      jdbc.update(
          "insert into "
              + s
              + ".brand_profile(id,user_id,business_name,created_at,updated_at) values(?,?,'Keep"
              + " brand',now(),now())",
          brand,
          user);
      jdbc.update(
          "insert into "
              + s
              + ".brand_context(id,brand_profile_id,context_version,profile_context,learned_patterns,evidence_refs,created_at,updated_at)"
              + " values(?,?,7,'{\"industry\":\"Beauty\"}','[\"legacy\"]','[]',now(),now())",
          ctx,
          brand);
      String before =
          jdbc.queryForObject(
              "select to_jsonb(c)::text from " + s + ".brand_context c where id=?",
              String.class,
              ctx);
      assertThat(config.target("26").load().migrate().migrationsExecuted).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select to_jsonb(c)::text from " + s + ".brand_context c where id=?",
                  String.class,
                  ctx))
          .isEqualTo(before);
      assertThat(jdbc.queryForObject("select count(*) from " + s + ".brand_context", Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select profile_revision from " + s + ".brand_profile where id=?",
                  Long.class,
                  brand))
          .isZero();
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "update " + s + ".brand_context set context_version=8 where id=?", ctx))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      jdbc.execute("drop schema " + s + " cascade");
    }
  }

  UUID review(UUID campaign, int rating) {
    UUID creator = UUID.randomUUID(),
        relationship = UUID.randomUUID(),
        collaboration = UUID.randomUUID(),
        outcome = UUID.randomUUID(),
        review = UUID.randomUUID();
    jdbc.update(
        "insert into creator(id,platform,external_id,user_name,created_at,updated_at)"
            + " values(?,'TIKTOK',?,'Channel',now(),now())",
        creator,
        creator.toString());
    jdbc.update(
        "insert into relationship(id,user_id,creator_id,campaign_id,stage,created_at,updated_at)"
            + " values(?,?,?,?,'COMPLETED',now(),now())",
        relationship,
        owner.getId(),
        creator,
        campaign);
    jdbc.update(
        "insert into"
            + " collaboration(id,relationship_id,campaign_id,creator_id,status,updated_at,completed_at)"
            + " values(?,?,?,?,'COMPLETED',now(),now())",
        collaboration,
        relationship,
        campaign,
        creator);
    jdbc.update(
        "insert into campaign_outcome(id,collaboration_id,recorded_at) values(?,?,now())",
        outcome,
        collaboration);
    jdbc.update(
        "insert into"
            + " creator_review(id,outcome_id,collaboration_id,reviewed_by_user_id,overall_rating,brand_fit_rating,reviewed_at)"
            + " values(?,?,?,?,5,?,now())",
        review,
        outcome,
        collaboration,
        owner.getId(),
        rating);
    return review;
  }

  @Test
  void learnedAggregatesAreIdempotentAndRecomputeFromCurrentReviews() throws Exception {
    UUID campaign = createCampaign(owner, profile());
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) ids.add(review(campaign, 5));
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    var first = tx.execute(s -> learned.snapshot(profile())).select("TIKTOK", "any");
    assertThat(first.reviewIds()).containsExactlyInAnyOrderElementsOf(ids);
    assertThat(first.score()).isEqualTo(100);
    String stored =
        jdbc.queryForObject(
            "select to_jsonb(p)::text from brand_pattern p where brand_profile_id=? and"
                + " pattern_type='BRAND_FIT_BY_PLATFORM_NICHE'",
            String.class,
            profile());
    tx.execute(s -> learned.snapshot(profile()));
    assertThat(
            jdbc.queryForObject(
                "select to_jsonb(p)::text from brand_pattern p where brand_profile_id=? and"
                    + " pattern_type='BRAND_FIT_BY_PLATFORM_NICHE'",
                String.class,
                profile()))
        .isEqualTo(stored);
    jdbc.update("update creator_review set brand_fit_rating=1 where id=?", ids.getFirst());
    assertThat(tx.execute(s -> learned.snapshot(profile())).select("TIKTOK", "any").score())
        .isEqualTo(80);
  }

  @Test
  void recommendationUsesOneContextAndPersistsWeightsWithoutRewritingHistory() throws Exception {
    UUID campaign = createCampaign(owner, profile());
    call(
        owner,
        patch("/campaigns/" + campaign + "/status"),
        Map.of("status", "READY_FOR_DISCOVERY"),
        200);
    java.util.UUID m3Source =
        jdbc.queryForObject(
            "select embedding_source_context_id from campaign_context where campaign_id=? and"
                + " active",
            java.util.UUID.class,
            campaign);
    var m3Lease =
        m3Store.claim(
            jdbc.queryForObject(
                "select id from campaign_embedding_job where context_id=?",
                java.util.UUID.class,
                m3Source));
    m3Store.finish(m3Lease, Collections.nCopies(768, 0.1f));
    for (int i = 0; i < 4; i++) review(campaign, 5);
    var c = mock(com.influencermatch.backend.creator.dto.RecommendationCandidateProjection.class);
    when(c.getCreatorId()).thenReturn(UUID.randomUUID());
    when(c.getPlatform()).thenReturn("TIKTOK");
    when(c.getNiche()).thenReturn("Beauty");
    when(c.getFollowers()).thenReturn(25000L);
    when(c.getSemanticScore()).thenReturn(0.7);
    when(candidateMetrics.findCandidatesByShortlist(
            any(), anyString(), anyBoolean(), anyCollection()))
        .thenReturn(List.of(c));
    when(ai.getEmbedding(anyString())).thenReturn(Collections.nCopies(768, 0.1f));
    when(ai.getExplanation(any()))
        .thenReturn(
            com.influencermatch.backend.recommendation.dto.ExplainResponseDto.builder()
                .summary("Fixture")
                .reasons(List.of())
                .explanationAvailable(false)
                .build());
    var first =
        request(post("/campaigns/" + campaign + "/ai-recommendations"), null, null, 200).data;
    UUID run = UUID.fromString(first.path("recommendationRunId").asText());
    var breakdown = first.path("recommendations").get(0).path("scoreBreakdown");
    assertThat(breakdown.path("m1Available").asBoolean()).isFalse();
    assertThat(breakdown.path("m4Available").asBoolean()).isFalse();
    assertThat(breakdown.path("effectiveWeights").path("M2").asDouble()).isEqualTo(1);
    String snapshot =
        jdbc.queryForObject(
            "select scoring_snapshot::text from recommendation_run where id=?", String.class, run);
    UUID old = context();
    review(campaign, 5);
    request(patch("/brands/me"), Map.of("positioning", "Changed brand"), tag, 200);
    var second =
        request(post("/campaigns/" + campaign + "/ai-recommendations"), null, null, 200)
            .data
            .path("recommendations")
            .get(0)
            .path("scoreBreakdown");
    assertThat(second.path("m4Available").asBoolean()).isTrue();
    assertThat(second.path("m4Score").asDouble()).isEqualTo(100);
    assertThat(second.path("m4EvidenceCount").asInt()).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "select brand_context_id from recommendation_run where id=?", UUID.class, run))
        .isEqualTo(old);
    assertThat(
            jdbc.queryForObject(
                "select scoring_snapshot::text from recommendation_run where id=?",
                String.class,
                run))
        .isEqualTo(snapshot);
  }

  @Test
  void patchPreservesOmittedFieldsAndOnlySemanticChangesAppendContext() throws Exception {
    UUID old = context();
    String payload =
        jdbc.queryForObject(
            "select profile_context::text from brand_context where id=?", String.class, old);
    var contact = request(patch("/brands/me"), Map.of("phoneNumber", "0987654321"), tag, 200);
    assertThat(contact.data.path("phoneNumber").asText()).isEqualTo("+84987654321");
    assertThat(contact.data.path("industry").asText()).isEqualTo("Beauty");
    assertThat(context()).isEqualTo(old);
    var changed =
        request(
            patch("/brands/me"),
            Map.of("positioning", " Natural ", "guidelines", "No unsupported claims"),
            contact.etag,
            200);
    assertThat(changed.data.path("m4Version").asInt()).isEqualTo(2);
    assertThat(context()).isNotEqualTo(old);
    assertThat(
            jdbc.queryForObject(
                "select profile_context::text from brand_context where id=?", String.class, old))
        .isEqualTo(payload);
    assertThat(payload).doesNotContain("phoneNumber", "0912345678", "userId");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from brand_embedding_job where context_id in (select id from"
                    + " brand_context where brand_profile_id=?)",
                Integer.class,
                profile()))
        .isEqualTo(2);
    var nulls = json.createObjectNode().putNull("positioning");
    var cleared = request(patch("/brands/me"), nulls, changed.etag, 200);
    assertThat(
            cleared.data.path("positioning").isMissingNode()
                || cleared.data.path("positioning").isNull())
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select positioning from brand_profile where id=?", String.class, profile()))
        .isNull();
  }

  @Test
  void canonicalNoOpDoesNotChangeRevisionOrCreateJob() throws Exception {
    var first =
        request(
            patch("/brands/me"), Map.of("productCategories", List.of("Skin", "Beauty")), tag, 200);
    var second =
        request(
            patch("/brands/me"),
            Map.of("productCategories", List.of(" Beauty ", "Skin", "Skin")),
            first.etag,
            200);
    assertThat(second.etag).isEqualTo(first.etag);
    assertThat(second.data.path("m4Version")).isEqualTo(first.data.path("m4Version"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from brand_context where brand_profile_id=?",
                Integer.class,
                profile()))
        .isEqualTo(2);
  }

  @Test
  void revisionValidationUnknownFieldsAndInvalidClearsNeverWrite() throws Exception {
    request(patch("/brands/me"), Map.of("industry", "Other"), null, 428);
    request(put("/brands/me"), Map.of("businessName", "Acme"), null, 428);
    request(post("/brands/me/onboarding/complete"), null, null, 428);
    request(patch("/brands/me"), Map.of("industry", "Other"), "*", 400);
    request(
        patch("/brands/me"), Map.of("industry", "Other"), "\"brand:" + profile() + ":r99\"", 409);
    request(patch("/brands/me"), Map.of("ownerId", UUID.randomUUID()), tag, 400);
    request(patch("/brands/me"), Map.of("preferredPlatforms", List.of("INVALID")), tag, 400);
    var done = request(post("/brands/me/onboarding/complete"), null, tag, 200);
    var twice = request(post("/brands/me/onboarding/complete"), null, done.etag, 200);
    assertThat(twice.etag).isEqualTo(done.etag);
    assertThat(twice.data.path("onboardingCompletedAt"))
        .isEqualTo(done.data.path("onboardingCompletedAt"));
    request(patch("/brands/me"), Map.of("productCategories", List.of()), done.etag, 400);
    assertThat(request(get("/brands/me"), null, null, 200).data.path("productCategories").size())
        .isEqualTo(1);
  }

  @Test
  void concurrentPatchOnlyOneRevisionWins() throws Exception {
    String token = jwt.generateToken(owner);
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> task =
          () -> {
            gate.await();
            return mvc.perform(
                    patch("/brands/me")
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", tag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positioning\":\"Changed\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var a = pool.submit(task);
      var b = pool.submit(task);
      gate.countDown();
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from brand_context where brand_profile_id=?",
                Integer.class,
                profile()))
        .isEqualTo(2);
  }

  @Test
  void enqueueFailureRollsBackProfileRevisionAndContext() throws Exception {
    UUID before = context();
    // A context-specific trigger forces failure only for this fixture, without affecting other
    // tests.
    String fn = "fail_enqueue_" + profile().toString().replace("-", "");
    jdbc.execute(
        "create function "
            + fn
            + "() returns trigger language plpgsql as $$ begin if exists(select 1 from"
            + " brand_context where id=new.context_id and brand_profile_id='"
            + profile()
            + "') then raise exception 'test enqueue failure'; end if; return new; end $$");
    jdbc.execute(
        "create trigger "
            + fn
            + " before insert on brand_embedding_job for each row execute function "
            + fn
            + "()");
    try {
      request(patch("/brands/me"), Map.of("positioning", "Must rollback"), tag, 500);
      assertThat(request(get("/brands/me"), null, null, 200).etag).isEqualTo(tag);
      assertThat(context()).isEqualTo(before);
    } finally {
      jdbc.execute("drop trigger " + fn + " on brand_embedding_job");
      jdbc.execute("drop function " + fn + "()");
    }
  }

  @Test
  void workerRetriesAndOldLeaseCannotOverwriteNewWorker() throws Exception {
    UUID id = job();
    var first = jobs.claim(id);
    assertThat(jobs.claim(id)).isNull();
    now = now.plusSeconds(121);
    var next = jobs.claim(id);
    assertThat(next.token()).isNotEqualTo(first.token());
    var vector = Collections.nCopies(768, 0.1f);
    assertThat(jobs.finish(first, vector)).isFalse();
    assertThat(jobs.finish(next, vector)).isTrue();
    assertThat(jobs.view(context()).status()).isEqualTo("READY");
    assertThat(jobs.claim(id)).isNull();
  }

  @Test
  void retryScheduleFailedJobAndCurrentContextOnly() throws Exception {
    UUID id = job();
    for (int i = 1; i <= 5; i++) {
      var lease = jobs.claim(id);
      assertThat(lease.attempts()).isEqualTo(i);
      jobs.fail(lease);
      if (i < 5) {
        assertThat(jobs.claim(id)).isNull();
        now = now.plusSeconds(15L << (i - 1));
      }
    }
    assertThat(jobs.view(context()).status()).isEqualTo("FAILED");
    request(post("/brands/me/context/embedding/retry"), null, null, 200);
    assertThat(jobs.claim(id).attempts()).isEqualTo(1);
    request(post("/brands/me/context/embedding/retry"), null, null, 409);
  }

  @Test
  void smtpIndependentWorkerCallsAiOutsideTransactionAndDoesNotChangeProfile() throws Exception {
    when(ai.getBrandEmbedding(anyString(), anyString()))
        .thenAnswer(
            i -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              assertThat(i.getArgument(0, String.class))
                  .doesNotContain("phoneNumber", "Acme", "+849");
              return Collections.nCopies(768, 0.1f);
            });
    worker.process(job());
    worker.process(job());
    verify(ai, times(1)).getBrandEmbedding(anyString(), anyString());
    assertThat(request(get("/brands/me"), null, null, 200).etag).isEqualTo(tag);
    request(patch("/brands/me"), Map.of("guidelines", "New context"), tag, 200);
    assertThat(jobs.view(context()).status()).isEqualTo("PENDING");
  }

  @Test
  void providerFailurePreservesProfileAndSchedulesDurableRetry() throws Exception {
    doThrow(new IllegalStateException("private-provider-response"))
        .when(ai)
        .getBrandEmbedding(anyString(), anyString());
    UUID id = job();
    worker.process(id);
    assertThat(request(get("/brands/me"), null, null, 200).etag).isEqualTo(tag);
    assertThat(jobs.view(context()).status()).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select attempts from brand_embedding_job where id=?", Integer.class, id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                    "select next_attempt_at from brand_embedding_job where id=?",
                    java.sql.Timestamp.class,
                    id)
                .toInstant())
        .isEqualTo(now.plusSeconds(15));
  }
}
