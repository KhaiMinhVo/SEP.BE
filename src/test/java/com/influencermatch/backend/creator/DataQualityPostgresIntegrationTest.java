package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.creator.enums.MetricDataStatus;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.security.JwtTokenProvider;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "application.bootstrap-admin.enabled=false", "application.oauth2.google.enabled=false",
    "spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=WARN"
})
@AutoConfigureMockMvc
@Testcontainers
class DataQualityPostgresIntegrationTest {
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired CreatorRepository creators;
  @Autowired PublicCreatorMetricRepository metrics;
  @Autowired JwtTokenProvider jwt;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  User user(Role role) {
    return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@quality.test").fullName("Reviewer")
        .role(role).status(UserStatus.ACTIVE).build());
  }
  Creator creator() {
    return creators.saveAndFlush(Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("koc").build());
  }
  PublicCreatorMetric metric(Creator c, String type, String confidence, LocalDateTime at) {
    return metrics.saveAndFlush(PublicCreatorMetric.builder().creator(c).creatorType(type)
        .dataConfidence(confidence == null ? null : new BigDecimal(confidence)).niche("Original")
        .contentSummary("Sample content").collectedAt(at).freshnessStatus(MetricDataStatus.FRESH).build());
  }
  MvcResult call(User user, MockHttpServletRequestBuilder request, String body, int status) throws Exception {
    if (user != null) request.header("Authorization", "Bearer " + jwt.generateToken(user));
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
    return mvc.perform(request).andExpect(status().is(status)).andReturn();
  }
  String path(PublicCreatorMetric m) { return "/admin/data-quality/"+m.getId()+"/approve"; }

  @Test void filtersLatestBeforeApplyingThresholdAndUsesUuidTieBreak() throws Exception {
    var reviewer = user(Role.DATA_MANAGER);
    var now = LocalDateTime.now();
    var eligible = metric(creator(), "KOC", "0.6999", now);
    var boundary = metric(creator(), "KOC", "0.7", now);
    var missing = metric(creator(), "KOC", null, now);
    var kol = metric(creator(), "KOL", "0.2", now);
    var c = creator();
    var old = metric(c, "KOC", "0.1", now.minusDays(1));
    var newer = metric(c, "KOC", "0.9", now);
    var tiedCreator = creator();
    var a = metric(tiedCreator, "KOC", "0.2", now);
    var b = metric(tiedCreator, "KOC", "0.3", now);
    String winner = jdbc.queryForObject("SELECT id::text FROM public_creator_metric WHERE creator_id=? ORDER BY collected_at DESC,id DESC LIMIT 1", String.class, tiedCreator.getId());
    var response = call(reviewer, get("/admin/data-quality/pending?size=1000"), null, 200);
    var data = json.readTree(response.getResponse().getContentAsString()).path("data");
    assertThat(data.path("size").asInt()).isEqualTo(100);
    List<String> ids = new ArrayList<>();
    data.path("items").forEach(item -> ids.add(item.path("metricId").asText()));
    assertThat(ids).contains(eligible.getId().toString(), winner)
        .doesNotContain(boundary.getId().toString(), missing.getId().toString(), kol.getId().toString(), old.getId().toString(), newer.getId().toString());
    assertThat(ids).doesNotContain(winner.equals(a.getId().toString()) ? b.getId().toString() : a.getId().toString());
  }

  @Test void approvalPreservesConfidenceRecordsAuditAndRequeuesNewSnapshot() throws Exception {
    var reviewer = user(Role.DATA_MANAGER);
    var c = creator();
    var m = metric(c, "KOC", "0.4", LocalDateTime.now().minusMinutes(1));
    call(reviewer, put(path(m)), "{\"niche\":\" Beauty \"}", 200);
    var saved = metrics.findById(m.getId()).orElseThrow();
    assertThat(saved.getNiche()).isEqualTo("Beauty");
    assertThat(saved.getDataConfidence()).isEqualByComparingTo("0.4");
    assertThat(saved.getQualityReviewedAt()).isNotNull();
    assertThat(jdbc.queryForObject("SELECT quality_reviewed_by FROM public_creator_metric WHERE id=?", UUID.class, m.getId())).isEqualTo(reviewer.getId());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='APPROVE_DATA_QUALITY'", Integer.class, m.getId())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT search_vector @@ plainto_tsquery('simple','Beauty') FROM public_creator_metric WHERE id=?", Boolean.class, m.getId())).isTrue();
    call(reviewer, put(path(m)), "{}", 409);
    var fresh = metric(c, "KOC", "0.3", LocalDateTime.now());
    var pending = call(reviewer, get("/admin/data-quality/pending?size=100"), null, 200);
    assertThat(pending.getResponse().getContentAsString()).contains(fresh.getId().toString()).doesNotContain(m.getId().toString());
    var result = call(reviewer, put(path(fresh)), "{}", 200);
    assertThat(json.readTree(result.getResponse().getContentAsString()).path("data").path("niche").asText()).isEqualTo("Original");
  }

  @Test void validatesRequestAndRejectsStaleOrIneligibleSnapshots() throws Exception {
    var reviewer = user(Role.ADMIN);
    var c = creator();
    var old = metric(c, "KOC", "0.3", LocalDateTime.now().minusDays(1));
    var current = metric(c, "KOC", "0.2", LocalDateTime.now());
    call(reviewer, put(path(old)), "{}", 409);
    call(reviewer, put(path(current)), "{\"niche\":\"  \"}", 400);
    call(reviewer, put(path(current)), json.writeValueAsString(Map.of("niche", "x".repeat(121))), 400);
    call(reviewer, put("/admin/data-quality/"+UUID.randomUUID()+"/approve"), "{}", 404);
    for (var m : List.of(metric(creator(), "KOL", "0.1", LocalDateTime.now()),
        metric(creator(), "KOC", "0.7", LocalDateTime.now()), metric(creator(), "KOC", null, LocalDateTime.now()))) {
      call(reviewer, put(path(m)), "{}", 409);
    }
    assertThat(metrics.findById(current.getId()).orElseThrow().getQualityReviewedAt()).isNull();
  }

  @Test void permissionsDoNotExposeOtherAdminRoutes() throws Exception {
    var m = metric(creator(), "KOC", "0.2", LocalDateTime.now());
    call(null, get("/admin/data-quality/pending"), null, 401);
    call(null, put(path(m)), "{}", 401);
    for (Role role : List.of(Role.BRAND, Role.CREATOR)) {
      var denied = user(role);
      call(denied, get("/admin/data-quality/pending"), null, 403);
      call(denied, put(path(m)), "{}", 403);
    }
    var manager = user(Role.DATA_MANAGER);
    call(manager, get("/admin/data-quality/pending"), null, 200);
    call(manager, get("/admin/users"), null, 403);
    call(user(Role.ADMIN), put(path(m)), "{}", 200);
  }

  @Test void concurrentApprovalHasOneWinner() throws Exception {
    var reviewer = user(Role.DATA_MANAGER);
    var m = metric(creator(), "KOC", "0.1", LocalDateTime.now());
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i=0; i<2; i++) results.add(pool.submit(() -> {
        start.await();
        return mvc.perform(put(path(m)).header("Authorization", "Bearer "+jwt.generateToken(reviewer))
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
      }));
      start.countDown();
      assertThat(List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200,409);
    }
  }

  @Test void ingestionLockForcesApprovalToRecheckLatestSnapshot() throws Exception {
    var reviewer = user(Role.DATA_MANAGER);
    var c = creator();
    var old = metric(c, "KOC", "0.2", LocalDateTime.now().minusDays(1));
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var ingestion = pool.submit(() -> tx.executeWithoutResult(s -> {
        creators.findLockedById(c.getId()).orElseThrow();
        locked.countDown();
        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        metric(c, "KOC", "0.1", LocalDateTime.now());
      }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      var approval = pool.submit(() -> call(reviewer, put(path(old)), "{}", 409));
      release.countDown();
      ingestion.get(20, TimeUnit.SECONDS);
      approval.get(20, TimeUnit.SECONDS);
    } finally { release.countDown(); }
    assertThat(metrics.findById(old.getId()).orElseThrow().getQualityReviewedAt()).isNull();
  }

  @Test void auditFailureRollsBackApprovalAndNiche() throws Exception {
    var reviewer = user(Role.ADMIN);
    var m = metric(creator(), "KOC", "0.2", LocalDateTime.now());
    // Database-side failure exercises the real transactional service and audit write.
    jdbc.execute("CREATE FUNCTION quality_test_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='APPROVE_DATA_QUALITY' THEN RAISE EXCEPTION 'test audit failure'; END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER quality_test_audit_failure BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION quality_test_audit_failure()");
    try { call(reviewer, put(path(m)), "{\"niche\":\"Changed\"}", 500); }
    finally {
      jdbc.execute("DROP TRIGGER quality_test_audit_failure ON audit_log");
      jdbc.execute("DROP FUNCTION quality_test_audit_failure()");
    }
    var saved = metrics.findById(m.getId()).orElseThrow();
    assertThat(saved.getQualityReviewedAt()).isNull();
    assertThat(saved.getNiche()).isEqualTo("Original");
  }
  @Test void paginationIsStableAndOrdersConfidenceThenTimeThenUuid() throws Exception {
    var reviewer=user(Role.DATA_MANAGER);var at=LocalDateTime.now().withNano(0).minusHours(2);
    var low=metric(creator(),"KOC","0.1233",at.plusMinutes(1));
    var early=metric(creator(),"KOC","0.3211",at);
    var tieA=metric(creator(),"KOC","0.3211",at.plusMinutes(1));
    var tieB=metric(creator(),"KOC","0.3211",at.plusMinutes(1));
    List<com.fasterxml.jackson.databind.JsonNode> all=new ArrayList<>();
    int pages=1;
    for(int page=0;page<pages;page++) {
      var data=json.readTree(call(reviewer,get("/admin/data-quality/pending?page="+page+"&size=2"),null,200)
          .getResponse().getContentAsString()).path("data");
      pages=data.path("totalPages").asInt();
      assertThat(data.path("page").asInt()).isEqualTo(page);
      assertThat(data.path("items").size()).isLessThanOrEqualTo(2);
      data.path("items").forEach(all::add);
    }
    var ids=all.stream().map(n->n.path("metricId").asText()).toList();
    assertThat(new HashSet<>(ids)).hasSize(ids.size());
    var tieIds=new ArrayList<>(List.of(tieA.getId().toString(),tieB.getId().toString()));Collections.sort(tieIds);
    var fixtureIds=Set.of(low.getId().toString(),early.getId().toString(),tieIds.get(0),tieIds.get(1));
    assertThat(ids.stream().filter(fixtureIds::contains).toList())
        .containsExactly(low.getId().toString(),early.getId().toString(),tieIds.get(0),tieIds.get(1));
    for(int i=1;i<all.size();i++) assertThat(all.get(i).path("dataConfidence").decimalValue())
        .isGreaterThanOrEqualTo(all.get(i-1).path("dataConfidence").decimalValue());
    var repeated=json.readTree(call(reviewer,get("/admin/data-quality/pending?page=0&size=2"),null,200)
        .getResponse().getContentAsString()).path("data").path("items");
    assertThat(repeated.get(0).path("metricId").asText()).isEqualTo(ids.get(0));
  }

  @Test void latestKolSuppressesOlderLowConfidenceKocWithoutWritingAudit() throws Exception {
    var reviewer=user(Role.DATA_MANAGER);var c=creator();
    var old=metric(c,"KOC","0.1",LocalDateTime.now().minusDays(1));
    var latest=metric(c,"KOL","0.1",LocalDateTime.now());
    var pending=call(reviewer,get("/admin/data-quality/pending?size=100"),null,200).getResponse().getContentAsString();
    assertThat(pending).doesNotContain(old.getId().toString(),latest.getId().toString());
    call(reviewer,put(path(old)),"{}",409);call(reviewer,put(path(latest)),"{}",409);
    for(var m:List.of(old,latest)) {
      assertThat(metrics.findById(m.getId()).orElseThrow().getQualityReviewedAt()).isNull();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='APPROVE_DATA_QUALITY'",Integer.class,m.getId())).isZero();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"0,400","120,200","121,400"})
  void nicheLengthAfterTrimmingAndRejectedRequestsAreAtomic(int length,int expected) throws Exception {
    var reviewer=user(Role.DATA_MANAGER);var m=metric(creator(),"KOC","0.2",LocalDateTime.now());
    String niche=" "+"x".repeat(length)+" ";
    call(reviewer,put(path(m)),json.writeValueAsString(Map.of("niche",niche)),expected);
    var stored=metrics.findById(m.getId()).orElseThrow();
    assertThat(stored.getNiche()).isEqualTo(expected==200?niche.trim():"Original");
    assertThat(stored.getDataConfidence()).isEqualByComparingTo("0.2");
    assertThat(stored.getQualityReviewedAt()!=null).isEqualTo(expected==200);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='APPROVE_DATA_QUALITY'",Integer.class,m.getId())).isEqualTo(expected==200?1:0);
  }

  @Test void emptyApprovalBodyKeepsNicheAndRecordsOneReview() throws Exception {
    var reviewer=user(Role.DATA_MANAGER);var m=metric(creator(),"KOC","0.4",LocalDateTime.now());
    call(reviewer,put(path(m)),"{}",200);
    var stored=metrics.findById(m.getId()).orElseThrow();
    assertThat(stored.getNiche()).isEqualTo("Original");
    assertThat(stored.getContentSummary()).isEqualTo("Sample content");
    assertThat(stored.getDataConfidence()).isEqualByComparingTo("0.4");
    assertThat(stored.getQualityReviewedAt()).isNotNull();
    call(reviewer,put(path(m)),"{}",409);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='APPROVE_DATA_QUALITY'",Integer.class,m.getId())).isEqualTo(1);
  }

}
