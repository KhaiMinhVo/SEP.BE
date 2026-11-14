package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.user.enums.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original Collaboration HTTP acceptance cases migrated to the explicit acceptance contract. */
class ExecutionContractPostgresIntegrationTest extends ExecutionScenarioSupport {
  @Test
  void completeSmokePersistsRelationsAndDoesNotChangeCampaignOrPayment() throws Exception {
    ready();
    var initial = state();
    var calc = initial.path("outcomeRevisions").get(1).path("calculation");
    assertThat(calc.path("views").asLong()).isEqualTo(1000);
    assertThat(calc.path("roas").decimalValue()).isEqualByComparingTo("5");
    assertThat(calc.path("roi").decimalValue()).isEqualByComparingTo("2");
    assertThat(initial.path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(initial.path("completedAt").isNull()).isTrue();
    assertThat(initial.path("paymentStatus").asText()).isEqualTo("PENDING");
    accept();
    request(brand, root + "/review", review(), 200);
    var finalState = state();
    assertThat(finalState.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(finalState.path("completedAt").isNull()).isFalse();
    assertThat(finalState.path("review").path("reviewedByUserId").asText())
        .isEqualTo(brand.getId().toString());
    assertThat(
            jdbc.queryForObject("select status from campaign where id=?", String.class, campaign))
        .isEqualTo("ACTIVE");
    assertThat(finalState.path("agreedFee").decimalValue()).isEqualByComparingTo("10000");
    assertThat(finalState.path("paymentStatus").asText()).isEqualTo("PENDING");
    request(
        brand,
        root + "/outcome/reopen",
        Map.of("expectedRevision", revision(), "reason", "Cannot reopen final"),
        409);
    request(brand, root + "/review", review(), 409);
  }

  @Test
  void securityOwnershipAndRelatedIdsAreEnforced() throws Exception {
    for (var role : List.of(Role.BRAND, Role.ADMIN, Role.DATA_MANAGER)) {
      var other = account(role);
      call(other, get(root + "/deliverables"), null, 403);
      request(
          other,
          root + "/outcome/accept",
          Map.of("expectedRevision", revision(), "outcomeRevision", 1),
          403);
    }
    call(null, get(root + "/deliverables"), null, 401);
    call(brand, get("/collaborations/" + UUID.randomUUID() + "/deliverables"), null, 404);
    request(
        brand,
        root + "/deliverables/" + UUID.randomUUID() + "/submissions",
        Map.of(
            "expectedRevision",
            1,
            "url",
            "https://evidence.test/draft",
            "source",
            "BRAND_EXTERNAL",
            "sourceDescription",
            "Email"),
        403);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "READY_FOR_DISCOVERY", "COMPLETED", "ARCHIVED"})
  void campaignWorkflowRejectsInvalidStates(String status) throws Exception {
    jdbc.update("update campaign set status=? where id=?", status, campaign);
    request(
        owner,
        root + "/deliverables/" + unit() + "/submissions",
        Map.of(
            "expectedRevision", 1, "url", "https://content.test/draft", "source", "CREATOR_PORTAL"),
        409);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_submission where deliverable_id=?",
                Integer.class,
                unit()))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"PLANNED", "AWAITING_REVIEW", "COMPLETED", "CANCELLED"})
  void collaborationWorkflowRejectsInvalidStates(String status) throws Exception {
    jdbc.update("update collaboration set status=? where id=?", status, collaboration);
    request(
        owner,
        root + "/deliverables/" + unit() + "/submissions",
        Map.of(
            "expectedRevision", 1, "url", "https://content.test/draft", "source", "CREATOR_PORTAL"),
        409);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "ftp://example.com/post",
        "javascript:alert(1)",
        "https://",
        "http://example.com/post",
        "https://user:password@example.com/post"
      })
  void invalidPostLinksDoNotWriteSubmission(String link) throws Exception {
    request(
        owner,
        root + "/deliverables/" + unit() + "/submissions",
        Map.of("expectedRevision", 1, "url", link, "source", "CREATOR_PORTAL"),
        400);
    assertThat(state().path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(state().path("publishedContentUrl").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_submission where deliverable_id=?",
                Integer.class,
                unit()))
        .isZero();
  }

  @ParameterizedTest
  @CsvSource({
    "null,null,200",
    "0,null,200",
    "null,0,200",
    "0,0,200",
    "-1,0,400",
    "0,-1,400",
    "1.2,0,400"
  })
  void outcomeMetricBoundariesNeverCompleteExecution(String views, String clicks, int code)
      throws Exception {
    submit();
    approve();
    publish(false);
    var post = json.valueToTree(measurement());
    ((ObjectNode) post).set("views", json.readTree(views));
    ((ObjectNode) post).set("clicks", json.readTree(clicks));
    var unavailable = ((ObjectNode) post).putArray("unavailableMetrics");
    if ("null".equals(views)) unavailable.add("views");
    if ("null".equals(clicks)) unavailable.add("clicks");
    request(
        owner,
        root + "/outcome",
        Map.of("expectedRevision", revision(), "reason", "Boundary test", "posts", List.of(post)),
        code);
    assertThat(state().path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(state().path("paymentStatus").asText()).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_outcome_revision where collaboration_id=?",
                Integer.class,
                collaboration))
        .isEqualTo(code == 200 ? 1 : 0);
    if (code == 200) {
      var calc = state().path("outcomeRevisions").get(0).path("calculation");
      assertThat(calc.path("views")).isEqualTo(json.readTree(views));
      assertThat(calc.path("clicks")).isEqualTo(json.readTree(clicks));
      assertThat(calc.path("actualCost").isNull()).isTrue();
      assertThat(calc.path("roi").isNull()).isTrue();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "overallRating,0",
    "overallRating,6",
    "brandFitRating,0",
    "brandFitRating,6",
    "overallRating,4.5",
    "brandFitRating,true",
    "overallRating,\"5\"",
    "brandFitRating,null",
    "wouldCollaborateAgain,null"
  })
  void invalidReviewStarsDoNotPersistReview(String field, String value) throws Exception {
    ready();
    accept();
    ObjectNode body = json.valueToTree(review());
    body.set(field, json.readTree(value));
    request(brand, root + "/review", body, 400);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_review where collaboration_id=?",
                Integer.class,
                collaboration))
        .isZero();
    assertThat(state().path("status").asText()).isEqualTo("AWAITING_REVIEW");
    assertThat(state().path("outcomeRevisions").size()).isEqualTo(2);
  }

  @Test
  void disabledPermissionAndChangedAuthVersionCannotMutateExecution() throws Exception {
    jdbc.update(
        "insert into role_permission_override(id,role,permission,enabled)"
            + " values(gen_random_uuid(),'CREATOR','RESPOND_OWN_COOPERATION',false) on"
            + " conflict(role,permission) do update set enabled=false");
    try {
      request(
          owner,
          root + "/deliverables/" + unit() + "/submissions",
          Map.of(
              "expectedRevision",
              1,
              "url",
              "https://content.test/draft",
              "source",
              "CREATOR_PORTAL"),
          403);
    } finally {
      jdbc.update(
          "delete from role_permission_override where role='CREATOR' and"
              + " permission='RESPOND_OWN_COOPERATION'");
    }
  }

  @Test
  void transactionRollbackRestoresSubmissionAndCollaboration() throws Exception {
    var target =
        (com.influencermatch.backend.audit.service.AuditService)
            org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
    doThrow(new IllegalStateException("audit failed"))
        .when(target)
        .record(eq("CONTENT_SUBMITTED"), any(), any(), any(), any(), any());
    request(
        owner,
        root + "/deliverables/" + unit() + "/submissions",
        Map.of(
            "expectedRevision", 1, "url", "https://content.test/draft", "source", "CREATOR_PORTAL"),
        500);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_submission where deliverable_id=?",
                Integer.class,
                unit()))
        .isZero();
    assertThat(state().path("deliverables").get(0).path("revision").asInt()).isEqualTo(1);
    assertThat(state().path("status").asText()).isEqualTo("IN_PROGRESS");
  }

  @Test
  void finalReviewConcurrencyCreatesOneReviewOutcomeAndHistory() throws Exception {
    ready();
    accept();
    var body = review();
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> jobs = new ArrayList<>();
      for (int n = 0; n < 2; n++)
        jobs.add(
            pool.submit(
                () -> {
                  start.await();
                  return mvc.perform(
                          post(root + "/review")
                              .header("Authorization", "Bearer " + jwt.generateToken(brand))
                              .header("Idempotency-Key", UUID.randomUUID().toString())
                              .contentType("application/json")
                              .content(json.writeValueAsString(body)))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      start.countDown();
      assertThat(
              List.of(jobs.get(0).get(30, TimeUnit.SECONDS), jobs.get(1).get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    for (String table : List.of("creator_review", "campaign_outcome", "historical_campaign"))
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + table + " where collaboration_id=?",
                  Integer.class,
                  collaboration))
          .isEqualTo(1);
  }

  @Test
  void immutableHistoryAndOneOutcomeAreDatabaseConstraints() throws Exception {
    ready();
    accept();
    request(brand, root + "/review", review(), 200);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update collaboration_submission set url='https://changed.test' where"
                        + " deliverable_id=?",
                    unit()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update collaboration_outcome_revision set reason='replaced' where"
                        + " collaboration_id=?",
                    collaboration))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update campaign_outcome set views=-1 where collaboration_id=?", collaboration))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into campaign_outcome(id,collaboration_id) values(?,?)",
                    UUID.randomUUID(),
                    collaboration))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void closingCampaignWaitsForCompletionAndExplicitBrandRequest() throws Exception {
    long r =
        jdbc.queryForObject(
            "select campaign_revision from campaign where id=?", Long.class, campaign);
    call(
        brand,
        patch("/campaigns/" + campaign + "/status")
            .header("If-Match", "\"campaign:" + campaign + ":r" + r + "\""),
        Map.of("status", "COMPLETED"),
        409);
    ready();
    accept();
    request(brand, root + "/review", review(), 200);
    assertThat(
            jdbc.queryForObject("select status from campaign where id=?", String.class, campaign))
        .isEqualTo("ACTIVE");
    call(
        brand,
        patch("/campaigns/" + campaign + "/status")
            .header("If-Match", "\"campaign:" + campaign + ":r" + r + "\""),
        Map.of("status", "COMPLETED"),
        200);
  }

  @Test
  void swaggerDocumentsTheProtectedExecutionOperations() throws Exception {
    var doc = call(null, get("/api-docs"), null, 200);
    for (String suffix :
        List.of("/deliverables", "/outcome", "/outcome/accept", "/outcome/reopen", "/review"))
      assertThat(doc.path("paths").path("/collaborations/{id}" + suffix).isMissingNode()).isFalse();
  }

  @Test
  void legacyCompletedReviewDoesNotInventContentAcceptance() throws Exception {
    jdbc.update(
        "update collaboration set execution_source='LEGACY',status='COMPLETED',completed_at=now()"
            + " where id=?",
        collaboration);
    UUID out = UUID.randomUUID();
    jdbc.update(
        "insert into campaign_outcome(id,collaboration_id,evidence_quality,views)"
            + " values(?,?,'SELF_REPORTED',100)",
        out,
        collaboration);
    request(
        brand,
        root + "/review/legacy",
        Map.of(
            "expectedRevision",
            revision(),
            "overallRating",
            3,
            "brandFitRating",
            2,
            "wouldCollaborateAgain",
            false,
            "reviewTags",
            List.of("legacy")),
        200);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_outcome_acceptance where collaboration_id=?",
                Integer.class,
                collaboration))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select provenance from historical_campaign where collaboration_id=?",
                String.class,
                collaboration))
        .isEqualTo("LEGACY_UNVERIFIED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-0.01", "1.001", "100000000000000000"})
  void legacyFeeBodyCannotBypassDealTerms(String fee) throws Exception {
    UUID relationship =
        jdbc.queryForObject(
            "select relationship_id from collaboration where id=?", UUID.class, collaboration);
    request(
        brand,
        "/campaigns/" + campaign + "/collaborations",
        Map.of("relationshipId", relationship, "agreedFee", json.readTree(fee), "currency", "VND"),
        400);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration where campaign_id=?", Integer.class, campaign))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select agreed_fee from collaboration where id=?",
                java.math.BigDecimal.class,
                collaboration))
        .isEqualByComparingTo("10000");
    assertThat(
            jdbc.queryForObject(
                "select stage from relationship where id=?", String.class, relationship))
        .isEqualTo("CONFIRMED");
  }
}
