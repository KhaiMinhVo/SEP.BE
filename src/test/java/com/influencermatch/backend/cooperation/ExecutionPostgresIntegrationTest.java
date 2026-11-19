package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.user.enums.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ExecutionPostgresIntegrationTest extends ExecutionScenarioSupport {
  @Test
  void savesEachPublicationAndKpiAtomicallyAndRequiresCompleteDeclarations() throws Exception {
    submit();
    approve();
    var firstUnit = unit();
    var firstPath = root + "/deliverables/" + firstUnit + "/outcome";
    var first = json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(measurement());
    first.remove("deliverableId");
    first.put("url", "https://www.tiktok.com/@test/video/123");
    first.put("expectedRevision", revision());
    first.put("views", 0);
    var incomplete = first.deepCopy();
    incomplete.remove("unavailableMetrics");
    request(brand, firstPath, first, 403);
    request(owner, firstPath, incomplete, 400);
    assertThat(state().path("deliverables").get(0).path("publications")).isEmpty();

    var badPeriod = first.deepCopy();
    badPeriod.put("periodEnd", "2026-10-31T00:00:00Z");
    request(owner, firstPath, badPeriod, 400);
    assertThat(revision()).isEqualTo(first.path("expectedRevision").asLong());
    assertThat(state().path("deliverables").get(0).path("publications")).isEmpty();
    assertThat(state().path("outcomeRevisions")).isEmpty();

    String key = UUID.randomUUID().toString();
    request(owner, firstPath, first, 200, key);
    request(owner, firstPath, first, 200, key);
    var result = state().path("outcomeRevisions");
    assertThat(result).hasSize(1);
    assertThat(result.get(0).path("reason").asText()).isEqualTo("Ghi nhận kết quả bài đăng lần đầu");
    var measurement = result.get(0).path("payload").path("posts").get(0);
    assertThat(measurement.path("views").asLong()).isZero();
    assertThat(measurement.path("unavailableMetrics").get(0).asText()).isEqualTo("clicks");
    request(owner, firstPath, first, 409);
    first.put("expectedRevision", revision());
    request(owner, firstPath, first, 400);
    first.remove("url");
    first.put("reason", "Corrected views");
    first.put("views", 50);
    request(owner, firstPath, first, 200);
    assertThat(state().path("deliverables").get(0).path("publications")).hasSize(1);

    request(brand, root + "/outcome/finance", Map.of(
        "expectedRevision", revision(), "currency", "VND", "financialConfirmed", true,
        "costs", List.of(Map.of("description", "Production", "amount", 1000))), 200);
    request(brand, root + "/outcome/finance", Map.of(
        "expectedRevision", revision(), "currency", "VND", "financialConfirmed", false), 400);
    var latest = state().path("outcomeRevisions").get(2);
    assertThat(latest.path("reason").asText()).isEqualTo("Ghi nhận tài chính lần đầu");

    // Simulate an incomplete historical revision while the collaboration is still open.
    var historical = (com.fasterxml.jackson.databind.node.ObjectNode) latest.path("payload").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) historical.path("posts").get(0)).remove("unavailableMetrics");
    jdbc.update(
        "insert into collaboration_outcome_revision(id,collaboration_id,revision,payload,calculation,actor_id,source,reason,created_at)"
            + " select gen_random_uuid(),collaboration_id,revision+1,?::jsonb,calculation,actor_id,source,reason,now()"
            + " from collaboration_outcome_revision where collaboration_id=? and revision=?",
        historical.toString(), collaboration, latest.path("revision").asLong());
    request(brand, root + "/outcome/accept", Map.of("expectedRevision", revision(), "outcomeRevision", latestOutcome()), 409);
    first.put("expectedRevision", revision());
    request(owner, firstPath, first, 200);
    assertThat(state().path("outcomeRevisions").get(4).path("calculation").path("actualCost").asLong()).isEqualTo(1000);
    accept();
  }

  @Test
  void fullWorkflowOnlyReviewCompletesAndDoesNotFabricateVerification() throws Exception {
    ready();
    assertThat(state().path("status").asText()).isEqualTo("IN_PROGRESS");
    accept();
    assertThat(state().path("status").asText()).isEqualTo("AWAITING_REVIEW");
    String key = UUID.randomUUID().toString();
    var body = review();
    request(brand, root + "/review", body, 200, key);
    request(brand, root + "/review", body, 200, key);
    assertThat(state().path("status").asText()).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_review where collaboration_id=?",
                Integer.class,
                collaboration))
        .isEqualTo(1);
    var history =
        jdbc.queryForMap(
            "select * from historical_campaign where collaboration_id=?", collaboration);
    assertThat(history.get("quality")).isEqualTo("SELF_REPORTED");
    assertThat(history.get("provenance")).isEqualTo("BRAND_ACCEPTED");
    assertThat(
            json.readTree(history.get("evidence_value").toString()).path("brandFitRating").asInt())
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select stage from relationship where id=(select relationship_id from collaboration"
                    + " where id=?)",
                String.class,
                collaboration))
        .isEqualTo("CONFIRMED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from brand_pattern where pattern_type='COLLABORATION_SUCCESS' and"
                    + " brand_profile_id=(select brand_profile_id from campaign where id=?)",
                Integer.class,
                campaign))
        .isZero();
  }

  @Test
  void rejectedChecklistAndRevisionLimitPreserveHistory() throws Exception {
    submit();
    request(
        brand,
        root + "/deliverables/" + unit() + "/review",
        Map.of(
            "expectedRevision",
            2,
            "submissionRevision",
            1,
            "decision",
            "APPROVE",
            "completedChecklist",
            List.of()),
        400);
    request(
        brand,
        root + "/deliverables/" + unit() + "/review",
        Map.of(
            "expectedRevision",
            2,
            "submissionRevision",
            1,
            "decision",
            "REQUEST_CHANGES",
            "completedChecklist",
            List.of(),
            "reason",
            "Fix caption"),
        200);
    dRevision = 3;
    submit();
    request(
        brand,
        root + "/deliverables/" + unit() + "/review",
        Map.of(
            "expectedRevision",
            4,
            "submissionRevision",
            2,
            "decision",
            "REQUEST_CHANGES",
            "completedChecklist",
            List.of(),
            "reason",
            "Again"),
        409);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_submission where deliverable_id=?",
                Integer.class,
                unit()))
        .isEqualTo(2);
  }

  @Test
  void earlyPublicationRequiresExplicitBrandException() throws Exception {
    submit();
    publish(false);
    approve();
    outcome();
    request(
        brand,
        root + "/outcome/accept",
        Map.of("expectedRevision", revision(), "outcomeRevision", latestOutcome()),
        409);
    publish(true);
    accept();
  }

  @Test
  void acceptsOnlyLatestOutcomeAndCanReopenBeforeFinalReview() throws Exception {
    ready();
    request(owner, root + "/outcome", Map.of("expectedRevision", revision(), "reason", "Updated measurements",
        "posts", List.of(measurement())), 200);
    request(
        brand,
        root + "/outcome/accept",
        Map.of("expectedRevision", revision(), "outcomeRevision", latestOutcome() - 1),
        409);
    request(
        brand,
        root + "/outcome/accept",
        Map.of("expectedRevision", revision(), "outcomeRevision", latestOutcome()),
        200);
    request(
        owner,
        root + "/outcome",
        Map.of(
            "expectedRevision", revision(), "reason", "Changed", "posts", List.of(measurement())),
        409);
    request(
        brand,
        root + "/outcome/reopen",
        Map.of("expectedRevision", revision(), "reason", "Correct measurement"),
        200);
    assertThat(state().path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_outcome_revision where collaboration_id=?",
                Integer.class,
                collaboration))
        .isEqualTo(3);
  }

  @Test
  void creatorCannotChangeMoneyOrAcceptOutcome() throws Exception {
    submit();
    approve();
    publish(false);
    request(
        owner,
        root + "/outcome",
        Map.of(
            "expectedRevision",
            revision(),
            "reason",
            "Creator data",
            "posts",
            List.of(measurement()),
            "costs",
            List.of()),
        403);
    request(
        owner,
        root + "/outcome/accept",
        Map.of("expectedRevision", revision(), "outcomeRevision", 1),
        403);
  }

  @Test
  void brandFinancePreservesCreatorKpiAndCreatorReadsAndReceiptsKeepFinancePrivate() throws Exception {
    var path = root + "/deliverables/" + unit();
    request(brand, path + "/submissions",
        Map.of("expectedRevision", 1, "url", "https://content.test/draft", "source", "CREATOR_PORTAL"), 403);
    request(brand, root + "/outcome",
        Map.of("expectedRevision", revision(), "reason", "Brand KPI", "posts", List.of(measurement())), 403);
    submit();
    request(brand, path + "/publication", Map.of("expectedRevision", 2, "submissionRevision", 1,
        "url", "https://www.tiktok.com/@test/video/123", "acknowledgeException", true,
        "exceptionReason", "Would create new publication"), 409);
    approve();
    publish(false);
    outcome();
    request(owner, root + "/outcome/finance",
        Map.of("expectedRevision", revision(), "reason", "Creator finance"), 403);
    request(brand, root + "/outcome/finance",
        Map.of("expectedRevision", revision(), "reason", "Override KPI", "posts", List.of(measurement())), 403);
    var kpi = Map.of("expectedRevision", revision(), "reason", "Creator updated",
        "posts", List.of(measurement()));
    var response = request(owner, root + "/outcome", kpi, 200, "private-kpi");
    assertThat(response.toString()).doesNotContain("actualCost", "financialConfirmed", "revenue", "roi", "reviewedByUserId");
    var full = state();
    assertThat(full.path("outcomeRevisions").get(2).path("calculation").path("actualCost").asLong())
        .isEqualTo(1000);
    jdbc.update("update cooperation_idempotency set response=?::jsonb where actor_id=? and key=?",
        full.toString(), owner.getId(), "private-kpi");
    assertThat(request(owner, root + "/outcome", kpi, 200, "private-kpi")).isEqualTo(response);
    accept();
    request(brand, root + "/review", review(), 200);
    var creator = call(owner, get(root + "/deliverables"), null, 200).path("data");
    assertThat(creator.toString()).doesNotContain("actualCost", "financialConfirmed", "revenue", "roi",
        "overallRating", "brandFitRating", "reviewTags");
    assertThat(creator.path("acceptances").size()).isEqualTo(1);
    assertThat(call(owner, get(root), null, 200).path("data").hasNonNull("review")).isFalse();
  }

  @Test
  void ownershipChangeRevokesPortalAndCannotForgeBrandProvenance() throws Exception {
    request(
        owner,
        root + "/deliverables/" + unit() + "/submissions",
        Map.of(
            "expectedRevision",
            1,
            "url",
            "https://content.test/draft",
            "source",
            "BRAND_EXTERNAL",
            "sourceDescription",
            "Fake Brand"),
        400);
    jdbc.update(
        "update creator set master_id=null,source_type='PUBLIC',authorized_at=null where id=?",
        channel);
    call(owner, get(root + "/deliverables"), null, 403);
    request(
        owner,
        root + "/deliverables/" + unit() + "/submissions",
        Map.of(
            "expectedRevision", 1, "url", "https://content.test/draft", "source", "CREATOR_PORTAL"),
        403);
  }

  @Test
  void auditFailureRollsBackReviewCompletionAndEvidence() throws Exception {
    ready();
    accept();
    doThrow(new IllegalStateException("audit unavailable"))
        .when(
            (AuditService)
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit))
        .record(eq("COLLABORATION_REVIEWED"), any(), any(), any(), any(), any());
    request(brand, root + "/review", review(), 500);
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
  }

  @Test
  void concurrentSubmissionsOnlyOneRevisionSucceeds() throws Exception {
    UUID d = unit();
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var tasks = new ArrayList<Future<Integer>>();
      for (int n = 0; n < 2; n++)
        tasks.add(
            pool.submit(
                () -> {
                  start.await();
                  var result =
                      mvc.perform(
                              post(root + "/deliverables/" + d + "/submissions")
                                  .header("Authorization", "Bearer " + jwt.generateToken(owner))
                                  .header("Idempotency-Key", UUID.randomUUID().toString())
                                  .contentType("application/json")
                                  .content(
                                      json.writeValueAsString(
                                          Map.of(
                                              "expectedRevision",
                                              1,
                                              "url",
                                              "https://content.test/draft",
                                              "source",
                                              "CREATOR_PORTAL"))))
                          .andReturn();
                  return result.getResponse().getStatus();
                }));
      start.countDown();
      assertThat(
              List.of(
                  tasks.get(0).get(30, TimeUnit.SECONDS), tasks.get(1).get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_submission where deliverable_id=?",
                Integer.class,
                d))
        .isEqualTo(1);
  }

  @Test
  void idempotencyDifferentBodyAndStaleRevisionAreRejected() throws Exception {
    UUID d = unit();
    String key = "submission-one";
    var body =
        Map.of(
            "expectedRevision", 1, "url", "https://content.test/draft", "source", "CREATOR_PORTAL");
    request(owner, root + "/deliverables/" + d + "/submissions", body, 200, key);
    request(owner, root + "/deliverables/" + d + "/submissions", body, 200, key);
    request(
        owner,
        root + "/deliverables/" + d + "/submissions",
        Map.of(
            "expectedRevision", 1, "url", "https://content.test/other", "source", "CREATOR_PORTAL"),
        409,
        key);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_submission where deliverable_id=?",
                Integer.class,
                d))
        .isEqualTo(1);
  }

  @Test
  void legacyPlanExpandsQuantityAndPreservesRecordedTerms() throws Exception {
    jdbc.update("delete from collaboration_deliverable where collaboration_id=?", collaboration);
    jdbc.update("update collaboration set execution_source='LEGACY' where id=?", collaboration);
    String key = "legacy-plan";
    var plan =
        Map.of(
            "expectedRevision",
            revision(),
            "sourceDescription",
            "Terms recorded from signed external agreement",
            "deliverables",
            List.of(
                Map.of(
                    "creatorId",
                    channel,
                    "contentType",
                    "Video",
                    "quantity",
                    2,
                    "dueAt",
                    "2027-01-01T00:00:00Z",
                    "acceptanceChecklist",
                    List.of("Brand safe"),
                    "maxRevisionRounds",
                    1)));
    request(brand, root + "/execution-plan", plan, 200, key);
    request(brand, root + "/execution-plan", plan, 200, key);
    assertThat(state().path("deliverables").size()).isEqualTo(2);
    assertThat(state().path("executionSource").asText()).isEqualTo("BRAND_LEGACY_PLAN");
    request(brand, root + "/execution-plan", plan, 409);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_outcome_acceptance where collaboration_id=?",
                Integer.class,
                collaboration))
        .isZero();
  }

  @Test
  void duplicatePublicationMeasurementsDoNotInflateOutcome() throws Exception {
    submit();
    approve();
    publish(false);
    request(
        owner,
        root + "/outcome",
        Map.of(
            "expectedRevision",
            revision(),
            "reason",
            "Duplicate",
            "posts",
            List.of(measurement(), measurement())),
        400);
    assertThat(state().path("outcomeRevisions").isEmpty()).isTrue();
  }

  @Test
  void evidenceAttachmentsCannotBypassContentReview() throws Exception {
    request(
        brand,
        root + "/outcome/accept",
        Map.of("expectedRevision", revision(), "outcomeRevision", 1),
        409);
    request(
        brand,
        root + "/review",
        Map.of(
            "expectedRevision",
            revision(),
            "outcomeRevision",
            1,
            "overallRating",
            5,
            "brandFitRating",
            5,
            "wouldCollaborateAgain",
            true,
            "reviewTags",
            List.of()),
        409);
    assertThat(state().path("status").asText()).isEqualTo("IN_PROGRESS");
  }

  @Test
  void recordOutcomeAuditFailureRollsBackRevision() throws Exception {
    submit();
    approve();
    publish(false);
    var target =
        (AuditService) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
    doThrow(new IllegalStateException("audit unavailable"))
        .when(target)
        .record(eq("OUTCOME_RECORDED"), any(), any(), any(), any(), any());
    request(
        owner,
        root + "/outcome",
        Map.of(
            "expectedRevision", revision(), "reason", "Measured", "posts", List.of(measurement())),
        500);
    assertThat(state().path("outcomeRevisions").isEmpty()).isTrue();
  }

  @Test
  void listsShowOutcomeBeforeFinalReviewWithoutClaimingCompletion() throws Exception {
    ready();
    var brandList =
        call(brand, get("/campaigns/" + campaign + "/collaborations"), null, 200)
            .path("data")
            .path("items")
            .get(0);
    assertThat(brandList.path("hasOutcome").asBoolean()).isTrue();
    assertThat(brandList.path("status").asText()).isEqualTo("IN_PROGRESS");
    var portal = call(owner, get("/creators/portal/collaborations"), null, 200).path("data").get(0);
    assertThat(portal.path("hasOutcome").asBoolean()).isTrue();
    assertThat(portal.path("hasReview").asBoolean()).isFalse();
  }

  @Test
  void missingIdempotencyHeaderIsAClientErrorNotInternalFailure() throws Exception {
    call(
        owner,
        post(root + "/deliverables/" + unit() + "/submissions"),
        Map.of(
            "expectedRevision", 1, "url", "https://content.test/draft", "source", "CREATOR_PORTAL"),
        400);
    assertThat(state().path("deliverables").get(0).path("revision").asInt()).isEqualTo(1);
  }
}
