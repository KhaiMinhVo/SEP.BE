package com.influencermatch.backend.campaign.history;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.security.jwt.JwtTokenProvider;
import com.influencermatch.backend.support.*;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Independent database: last-quota races must not change subscriptions used by other modules. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "application.bootstrap-admin.enabled=false",
      "application.claim-email.dispatch-enabled=false",
      "application.meta.cleanup-enabled=false",
      "application.tiktok.cleanup-enabled=false",
      "spring.jpa.show-sql=false"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class HistoryQuotaPostgresIntegrationTest {
  static final PostgreSQLContainer<?> DB =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  static {
    DB.start();
  }

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired JwtTokenProvider jwt;
  @MockBean com.influencermatch.backend.creator.client.CrawlerServiceClient crawler;
  @MockBean com.influencermatch.backend.auth.service.AuthSessionCleanup cleanup;
  @SpyBean AuditService audit;
  User actor;
  UUID brand, source, subscription;
  String token;

  @BeforeEach
  void fixture() {
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    actor = TestActors.user(Role.BRAND);
    actor.setId(null);
    actor = users.saveAndFlush(actor);
    brand = UUID.randomUUID();
    source = UUID.randomUUID();
    subscription = UUID.randomUUID();
    UUID plan = UUID.randomUUID();
    jdbc.update(
        "insert into brand_profile(id,user_id,business_name,created_at,updated_at)"
            + " values(?,?,'Quota Brand',now(),now())",
        brand,
        actor.getId());
    jdbc.update(
        "insert into campaign(id,brand_profile_id,name,status,created_at,updated_at)"
            + " values(?,?,'Quota source','DRAFT',now(),now())",
        source,
        brand);
    jdbc.update(
        "insert into"
            + " plan(id,plan_name,price,currency,duration_days,campaign_quota,ai_recommendation_quota,refresh_quota,status,created_at)"
            + " values(?,?,0,'VND',365,2,100,100,'ACTIVE',now())",
        plan,
        "quota-" + plan);
    jdbc.update(
        "insert into"
            + " subscription(id,brand_profile_id,plan_id,start_date,expiration_date,status,used_campaign_quota,used_ai_quota,used_refresh_quota)"
            + " values(?,?,?,current_date,current_date+365,'ACTIVE',1,0,0)",
        subscription,
        brand,
        plan);
    token = jwt.generateToken(actor);
  }

  Map<String, Object> body() {
    return Map.of(
        "expectedSourceRevision",
        0,
        "sourceKind",
        "CURRENT_DRAFT",
        "draft",
        Map.of("name", "Copied"));
  }

  org.springframework.mock.web.MockHttpServletResponse perform(
      MockHttpServletRequestBuilder req, Object body) throws Exception {
    req.header("Authorization", "Bearer " + token);
    if (body != null) req.contentType("application/json").content(json.writeValueAsBytes(body));
    return mvc.perform(req.with(TestAuthHttp.request(body))).andReturn().getResponse();
  }

  String path() {
    return "/campaigns/" + source + "/duplicate-brief";
  }

  List<org.springframework.mock.web.MockHttpServletResponse> race(
      Callable<org.springframework.mock.web.MockHttpServletResponse> a,
      Callable<org.springframework.mock.web.MockHttpServletResponse> b)
      throws Exception {
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var one =
          pool.submit(
              () -> {
                start.await();
                return a.call();
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                return b.call();
              });
      start.countDown();
      return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void ordinaryCreateAndDuplicateCompeteAtomicallyForLastQuota() throws Exception {
    var results =
        race(
            () -> perform(post("/brands/" + brand + "/campaigns"), Map.of("name", "Ordinary")),
            () -> perform(post(path()).header("Idempotency-Key", "last"), body()));
    assertThat(results.stream().map(r -> r.getStatus()).toList())
        .containsExactlyInAnyOrder(201, 409);
    assertThat(
            jdbc.queryForObject(
                "select used_campaign_quota from subscription where id=?",
                Integer.class,
                subscription))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from campaign where brand_profile_id=?", Integer.class, brand))
        .isEqualTo(2);
  }

  @Test
  void sameKeyConcurrentRetryConsumesOneQuotaAndReplaysEvenWhenFull() throws Exception {
    var results =
        race(
            () -> perform(post(path()).header("Idempotency-Key", "same"), body()),
            () -> perform(post(path()).header("Idempotency-Key", "same"), body()));
    assertThat(results.stream().map(r -> r.getStatus()).toList()).containsOnly(201);
    assertThat(json.readTree(results.get(0).getContentAsString()).path("data"))
        .isEqualTo(json.readTree(results.get(1).getContentAsString()).path("data"));
    assertThat(perform(post(path()).header("Idempotency-Key", "same"), body()).getStatus())
        .isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "select used_campaign_quota from subscription where id=?",
                Integer.class,
                subscription))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from campaign where brand_profile_id=?", Integer.class, brand))
        .isEqualTo(2);
  }

  @Test
  void previewAndAuditRollbackLeaveQuotaAndCampaignUnchanged() throws Exception {
    assertThat(perform(get(path() + "/preview"), null).getStatus()).isEqualTo(200);
    doThrow(new IllegalStateException("audit failure"))
        .when(
            (com.influencermatch.backend.audit.service.AuditService)
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit))
        .record(eq("DUPLICATE_CAMPAIGN_BRIEF"), anyString(), any(), any(), any(), anyString());
    assertThat(perform(post(path()).header("Idempotency-Key", "rollback"), body()).getStatus())
        .isEqualTo(500);
    assertThat(
            jdbc.queryForObject(
                "select used_campaign_quota from subscription where id=?",
                Integer.class,
                subscription))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from campaign where brand_profile_id=?", Integer.class, brand))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from campaign_duplicate_request where actor_id=?",
                Integer.class,
                actor.getId()))
        .isZero();
  }

  @Test
  void migrationV29ToV30PreservesStoredDataAndCreatesConstrainedEmptyReceipts() {
    String schema = "history_" + UUID.randomUUID().toString().replace("-", "");
    var initial =
        org.flywaydb.core.Flyway.configure()
            .dataSource(jdbc.getDataSource())
            .schemas(schema)
            .defaultSchema(schema)
            .target("29")
            .load();
    initial.migrate();
    try {
      // Copy real V29 fixtures and immutable execution evidence, in FK order, without creating
      // origins.
      for (String table : List.of("user", "brand_profile", "campaign"))
        jdbc.execute(
            "insert into "
                + schema
                + ".\""
                + table
                + "\" select * from public.\""
                + table
                + "\" on conflict do nothing");
      UUID channel = UUID.randomUUID(),
          relationship = UUID.randomUUID(),
          collaboration = UUID.randomUUID();
      jdbc.update(
          "insert into "
              + schema
              + ".creator(id,platform,external_id,user_name,created_at,updated_at)"
              + " values(?,'TIKTOK','123456','legacy',now(),now())",
          channel);
      jdbc.update(
          "insert into "
              + schema
              + ".relationship(id,user_id,creator_id,campaign_id,stage,created_at,updated_at)"
              + " values(?,?,?,?,'CONFIRMED',now(),now())",
          relationship,
          actor.getId(),
          channel,
          source);
      jdbc.update(
          "insert into "
              + schema
              + ".collaboration(id,relationship_id,campaign_id,creator_id,status,agreed_fee,currency,updated_at)"
              + " values(?,?,?,?,'COMPLETED',999,'VND',now())",
          collaboration,
          relationship,
          source,
          channel);
      jdbc.update(
          "insert into "
              + schema
              + ".cooperation_deal(id,campaign_id,brand_user_id,recipient_key,status,source,collaboration_id,created_at,updated_at)"
              + " values(?,?,?,?,'CONFIRMED','LEGACY',?,now(),now())",
          UUID.randomUUID(),
          source,
          actor.getId(),
          "CHANNEL:" + channel,
          collaboration);
      jdbc.update(
          "insert into "
              + schema
              + ".campaign_context(id,campaign_id,context_version,context_data,active,created_at,updated_at)"
              + " values(?,?,1,'{}',true,now(),now())",
          UUID.randomUUID(),
          source);
      jdbc.update(
          "insert into "
              + schema
              + ".collaboration_outcome_revision(id,collaboration_id,revision,payload,calculation,actor_id,reason,created_at)"
              + " values(?,?,1,'{}','{}',?,'Stored evidence',now())",
          UUID.randomUUID(),
          collaboration,
          actor.getId());
      var before = new LinkedHashMap<String, List<String>>();
      for (String table :
          List.of(
              "campaign",
              "campaign_context",
              "cooperation_deal",
              "relationship",
              "collaboration",
              "collaboration_outcome_revision"))
        before.put(
            table,
            jdbc.queryForList(
                "select to_jsonb(t)::text from " + schema + "." + table + " t order by id",
                String.class));
      assertThat(
              org.flywaydb.core.Flyway.configure()
                  .dataSource(jdbc.getDataSource())
                  .schemas(schema)
                  .defaultSchema(schema)
                  .load()
                  .migrate()
                  .migrationsExecuted)
          .isEqualTo(1);
      before.forEach(
          (table, rows) ->
              assertThat(
                      jdbc.queryForList(
                          "select to_jsonb(t)::text from "
                              + schema
                              + "."
                              + table
                              + " t order by id",
                          String.class))
                  .as(table)
                  .isEqualTo(rows));
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + schema + ".campaign_duplicate_request", Long.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from information_schema.table_constraints where table_schema=?"
                      + " and table_name='campaign_duplicate_request' and constraint_type='FOREIGN"
                      + " KEY'",
                  Long.class,
                  schema))
          .isEqualTo(4);
    } finally {
      jdbc.execute("drop schema " + schema + " cascade");
    }
  }

  @Test
  void receiptInsertFailureRollsBackQuotaAndTarget() throws Exception {
    jdbc.execute(
        "create function fail_history_receipt() returns trigger language plpgsql as $$ begin raise"
            + " exception 'receipt failure'; end $$");
    jdbc.execute(
        "create trigger fail_history_receipt before insert on campaign_duplicate_request for each"
            + " row execute function fail_history_receipt()");
    try {
      assertThat(
              perform(post(path()).header("Idempotency-Key", "receipt-failure"), body())
                  .getStatus())
          .isEqualTo(500);
      assertThat(
              jdbc.queryForObject(
                  "select used_campaign_quota from subscription where id=?",
                  Integer.class,
                  subscription))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from campaign where brand_profile_id=?", Integer.class, brand))
          .isEqualTo(1);
    } finally {
      jdbc.execute("drop trigger fail_history_receipt on campaign_duplicate_request");
      jdbc.execute("drop function fail_history_receipt()");
    }
  }
}
