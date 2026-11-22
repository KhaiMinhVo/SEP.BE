package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.user.enums.Role;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlexibleExecutionPostgresIntegrationTest extends ExecutionScenarioSupport {
  @Override boolean startImmediately() { return false; }

  void mode(String value) throws Exception {
    request(brand, root + "/management-mode", Map.of("expectedRevision", revision(), "managementMode", value), 200);
  }
  void start() throws Exception {
    long rev = jdbc.queryForObject("select revision from cooperation_deal where id=?", Long.class, deal);
    request(brand, "/campaigns/" + campaign + "/deals/" + deal + "/start", Map.of("expectedRevision", rev), 200);
  }
  Map<String,Object> externalSubmission() {
    return Map.of("expectedRevision", dRevision, "url", "https://content.test/external-draft",
        "sourceDescription", "Received via email", "receivedAt", Instant.now().minusSeconds(60).toString());
  }
  void externalReady() throws Exception {
    mode("BRAND_MANAGED"); start();
    request(brand, root + "/deliverables/" + unit() + "/external-submissions", externalSubmission(), 200);
    dRevision++; approve();
    request(brand, root + "/deliverables/" + unit() + "/external-publication",
        Map.of("expectedRevision", dRevision, "submissionRevision", 1, "url", "https://www.tiktok.com/@test/video/123", "sourceDescription", "Creator shared link by email"), 200);
    dRevision++;
    var body = new LinkedHashMap<String,Object>(measurement());
    body.remove("deliverableId"); body.put("expectedRevision", revision()); body.put("sourceDescription", "External analytics report");
    request(brand, root + "/deliverables/" + unit() + "/external-outcome", body, 200);
    request(brand, root + "/outcome/finance", Map.of("expectedRevision", revision(), "costs",
        List.of(Map.of("description", "Production", "amount", 1000)), "currency", "VND", "financialConfirmed", true), 200);
  }

  @Test void unclaimedChannelCanStartBrandManagedWithoutFakeCreatorAssignment() throws Exception {
    jdbc.update("update creator set master_id=null,source_type='PUBLIC',authorized_at=null where id=?", channel);
    jdbc.update("update collaboration set master_id=null where id=?", collaboration);
    jdbc.update("update cooperation_deal set master_id=null where id=?", deal);
    mode("BRAND_MANAGED"); start();
    assertThat(state().path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(jdbc.queryForObject("select master_id from collaboration where id=?", UUID.class, collaboration)).isNull();
    request(brand, root + "/deliverables/" + unit() + "/external-submissions", externalSubmission(), 200);
    assertThat(jdbc.queryForObject("select source from collaboration_submission where deliverable_id=?", String.class, unit())).isEqualTo("BRAND_EXTERNAL");
  }

  @Test void modeIsRevisionAwareAndFrozenAfterStart() throws Exception {
    request(brand, root + "/management-mode", Map.of("expectedRevision", 2.5, "managementMode", "BRAND_MANAGED"), 400);
    request(brand, root + "/management-mode", Map.of("expectedRevision", revision()+1, "managementMode", "BRAND_MANAGED"), 409);
    var other = account(Role.BRAND);
    request(other, root + "/management-mode", Map.of("expectedRevision", revision(), "managementMode", "BRAND_MANAGED"), 403);
    long before = revision();
    var body = Map.of("expectedRevision", before, "managementMode", "BRAND_MANAGED");
    String key = UUID.randomUUID().toString();
    request(brand, root + "/management-mode", body, 200, key);
    request(brand, root + "/management-mode", body, 200, key);
    assertThat(revision()).isEqualTo(before + 1);
    start();
    request(brand, root + "/management-mode", Map.of("expectedRevision", revision(), "managementMode", "COLLABORATIVE"), 409);
  }

  @Test void wrongRecorderCannotBypassModeAndFutureReceiptsAreRejected() throws Exception {
    mode("COLLABORATIVE"); start();
    request(brand, root + "/deliverables/" + unit() + "/external-submissions", externalSubmission(), 403);
    // A distinct collaboration fixture is unnecessary: change only the test precondition before writing.
    jdbc.update("update collaboration set management_mode='BRAND_MANAGED' where id=?", collaboration);
    request(owner, root + "/deliverables/" + unit() + "/submissions",
        Map.of("expectedRevision", 1, "url", "https://content.test/draft", "source", "CREATOR_PORTAL"), 403);
    var future = new LinkedHashMap<String,Object>(externalSubmission());
    future.put("receivedAt", Instant.now().plusSeconds(3600).toString());
    request(brand, root + "/deliverables/" + unit() + "/external-submissions", future, 400);
    assertThat(state().path("deliverables").get(0).path("submissions").size()).isZero();
  }

  @Test void externalOutcomeCompletesWithoutReviewAndKeepsProvenance() throws Exception {
    externalReady();
    var posts = state().path("outcomeRevisions").get(1).path("payload").path("posts");
    assertThat(posts.get(0).path("metricSources").path("views").asText()).isEqualTo("BRAND_EXTERNAL_REPORTED");
    request(brand, root + "/complete-without-review", Map.of("expectedRevision", revision(), "outcomeRevision", latestOutcome()), 409);
    accept();
    var body = Map.of("expectedRevision", revision(), "outcomeRevision", latestOutcome());
    String key = UUID.randomUUID().toString();
    request(brand, root + "/complete-without-review", body, 200, key);
    request(brand, root + "/complete-without-review", body, 200, key);
    assertThat(state().path("completionMethod").asText()).isEqualTo("WITHOUT_REVIEW");
    assertThat(state().path("status").asText()).isEqualTo("COMPLETED");
    assertThat(jdbc.queryForObject("select count(*) from creator_review where collaboration_id=?", Long.class, collaboration)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from historical_campaign where collaboration_id=?", Long.class, collaboration)).isEqualTo(1);
    String evidence = jdbc.queryForObject("select evidence_value::text from historical_campaign where collaboration_id=?", String.class, collaboration);
    assertThat(json.readTree(evidence).path("evidenceSchemaVersion").asText()).isEqualTo("collaboration-completion-evidence-v1");
    assertThat(json.readTree(evidence).path("completionMethod").asText()).isEqualTo("WITHOUT_REVIEW");
    assertThat(json.readTree(evidence).path("kpiStatus").asText()).isEqualTo("NOT_APPLICABLE");
    assertThat(json.readTree(evidence).path("deliverables").get(0).path("submittedAt").isNull()).isTrue();
    assertThat(jdbc.queryForObject(
        "select business_occurred_at=(select accepted_at from collaboration_outcome_acceptance"
            + " where collaboration_id=? and reopened_at is null) from historical_campaign"
            + " where collaboration_id=?",
        Boolean.class, collaboration, collaboration)).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from campaign_outcome where collaboration_id=?", Long.class, collaboration)).isEqualTo(1);
    assertThat(state().path("paymentStatus").asText()).isEqualTo("PENDING");
    var report = call(brand, get("/campaigns/" + campaign + "/history"), null, 200).path("data").path("report");
    assertThat(report.path("eligibleCount").asInt()).isEqualTo(1);
    assertThat(report.path("metrics").path("actualCost").path("value").asInt()).isEqualTo(1000);
    campaignStatus("COMPLETED", null, 200);
  }

  @Test void externalReviewRemainsAnActualBrandReviewWithExternalMetricProvenance() throws Exception {
    externalReady(); accept();
    request(brand, root + "/review", review(), 200);
    assertThat(state().path("status").asText()).isEqualTo("COMPLETED");
    assertThat(state().path("completionMethod").asText()).isEqualTo("WITH_REVIEW");
    assertThat(jdbc.queryForObject("select count(*) from creator_review where collaboration_id=?", Long.class, collaboration)).isEqualTo(1);
    String evidence = jdbc.queryForObject("select evidence_value::text from historical_campaign where collaboration_id=?", String.class, collaboration);
    assertThat(json.readTree(evidence).path("metricSource").asText()).isEqualTo("BRAND_EXTERNAL_REPORTED");
    assertThat(json.readTree(evidence).path("evidenceSchemaVersion").asText()).isEqualTo("collaboration-completion-evidence-v1");
    assertThat(json.readTree(evidence).path("overallRating").asInt()).isEqualTo(
        jdbc.queryForObject("select overall_rating from creator_review where collaboration_id=?",
            Integer.class, collaboration));
    assertThat(json.readTree(evidence).path("outcomeRevision").asLong()).isEqualTo(
        jdbc.queryForObject("select accepted_outcome_revision from creator_review"
            + " where collaboration_id=?", Long.class, collaboration));
    assertThat(jdbc.queryForObject(
        "select source_review_id=(select id from creator_review where collaboration_id=?)"
            + " and business_occurred_at=(select accepted_at from collaboration_outcome_acceptance"
            + " where collaboration_id=? and reopened_at is null) from historical_campaign"
            + " where collaboration_id=?",
        Boolean.class, collaboration, collaboration, collaboration)).isTrue();
  }

  @Test void creatorRequestsBrandDeclinesThenTerminatesWithoutLosingWorkOrCosts() throws Exception {
    externalReady();
    long before = latestOutcome();
    var expense = jdbc.queryForObject("select agreed_fee from collaboration where id=?", java.math.BigDecimal.class, collaboration);
    request(owner, root + "/termination-requests", Map.of("expectedRevision", revision(), "reason", "Cannot continue"), 200);
    var requestId = state().path("exitRequests").get(0).path("id").asText();
    assertThat(state().path("status").asText()).isEqualTo("IN_PROGRESS");
    request(brand, root + "/outcome/accept", Map.of("expectedRevision", revision(), "outcomeRevision", latestOutcome()), 409);
    request(brand, root + "/termination-requests/decline", Map.of("expectedRevision", revision(), "requestId", requestId, "reason", "Continue discussions"), 200);
    request(owner, root + "/termination-requests", Map.of("expectedRevision", revision(), "reason", "Still unavailable"), 200);
    request(owner, root + "/terminate", Map.of("expectedRevision", revision(), "requestId",
        state().path("exitRequests").get(1).path("id").asText(), "reason", "Cannot approve own request"), 400);
    var pendingId = state().path("exitRequests").get(1).path("id").asText();
    var body = Map.of("expectedRevision", revision(), "requestId", pendingId,
        "reason", "Agreed to stop externally", "evidenceUrl", "https://evidence.test/termination");
    String key = UUID.randomUUID().toString();
    request(brand, root + "/terminate", body, 200, key);
    request(brand, root + "/terminate", body, 200, key);
    assertThat(state().path("status").asText()).isEqualTo("TERMINATED");
    assertThat(latestOutcome()).isEqualTo(before);
    assertThat(jdbc.queryForObject("select agreed_fee from collaboration where id=?", java.math.BigDecimal.class, collaboration)).isEqualByComparingTo(expense);
    assertThat(state().path("deliverables").get(0).path("submissions").size()).isEqualTo(1);
    assertThat(state().path("paymentStatus").asText()).isEqualTo("PENDING");
    campaignStatus("COMPLETED", null, 409);
    campaignStatus("CLOSED", "No successful collaboration", 200);
    assertThat(jdbc.queryForObject("select closure_reason from campaign where id=?", String.class, campaign)).isEqualTo("No successful collaboration");
  }

  @Test void eitherParticipantMayRequestButOnlyTheOtherMayConsent() throws Exception {
    externalReady();
    request(brand, root + "/termination-requests",
        Map.of("expectedRevision", revision(), "reason", "Brand proposes stopping"), 200);
    var requestId = state().path("exitRequests").get(0).path("id").asText();
    request(brand, root + "/terminate",
        Map.of("expectedRevision", revision(), "requestId", requestId, "reason", "Cannot approve own request"), 400);
    request(owner, root + "/terminate",
        Map.of("expectedRevision", revision(), "requestId", requestId, "reason", "Agreed to stop"), 200);
    assertThat(state().path("status").asText()).isEqualTo("TERMINATED");
    assertThat(state().path("exitRequests").get(0).path("resolution").asText()).isEqualTo("TERMINATED");
  }

  @Test void closingCampaignRequiresReasonAndNoOpenWork() throws Exception {
    campaignStatus("CLOSED", "Too early", 409);
    long dealRevision = jdbc.queryForObject("select revision from cooperation_deal where id=?", Long.class, deal);
    request(brand, "/campaigns/" + campaign + "/deals/" + deal + "/cancel", Map.of("expectedRevision", dealRevision, "reason", "Not proceeding"), 200);
    campaignStatus("CLOSED", null, 400);
    campaignStatus("CLOSED", "Brand no longer proceeding", 200);
  }

  @Test void externalContentRevisionHistoryAndSourcesRemainImmutable() throws Exception {
    mode("BRAND_MANAGED"); start();
    var first = externalSubmission();
    String key = UUID.randomUUID().toString();
    request(brand, root + "/deliverables/" + unit() + "/external-submissions", first, 200, key);
    request(brand, root + "/deliverables/" + unit() + "/external-submissions", first, 200, key);
    dRevision++;
    request(brand, root + "/deliverables/" + unit() + "/review", Map.of("expectedRevision", dRevision,
        "submissionRevision", 1, "decision", "REQUEST_CHANGES", "completedChecklist", List.of(), "reason", "Change ending"), 200);
    dRevision++;
    request(brand, root + "/deliverables/" + unit() + "/external-submissions", externalSubmission(), 200);
    var submissions = state().path("deliverables").get(0).path("submissions");
    assertThat(submissions.size()).isEqualTo(2);
    assertThat(submissions.get(0).path("receivedAt").asText()).isNotBlank();
    assertThat(submissions.get(0).path("sourceDescription").asText()).isEqualTo("Received via email");
    assertThat(submissions.get(0).path("source").asText()).isEqualTo("BRAND_EXTERNAL");
  }

  void campaignStatus(String status, String reason, int expected) throws Exception {
    long rev = jdbc.queryForObject("select campaign_revision from campaign where id=?", Long.class, campaign);
    var body = new LinkedHashMap<String,Object>(); body.put("status", status); if (reason != null) body.put("reason", reason);
    call(brand, patch("/campaigns/" + campaign + "/status").header("If-Match", "\"campaign:" + campaign + ":r" + rev + "\""), body, expected);
  }
}
