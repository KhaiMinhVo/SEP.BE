package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CampaignHistoryPostgresIntegrationTest extends ExecutionScenarioSupport {
  @org.springframework.boot.test.mock.mockito.SpyBean
  com.influencermatch.backend.campaign.service.CampaignService campaignService;

  String history() {
    return "/campaigns/" + campaign + "/history";
  }

  String duplicate() {
    return "/campaigns/" + campaign + "/duplicate-brief";
  }

  JsonNode read(String path) throws Exception {
    return call(brand, get(path), null, 200).path("data");
  }

  ObjectNode copyBody() throws Exception {
    var preview = read(duplicate() + "/preview");
    var body = json.createObjectNode();
    body.put("expectedSourceRevision", preview.path("source").path("sourceRevision").asLong());
    body.set("sourceKind", preview.path("source").get("sourceKind"));
    body.set("sourceContextId", preview.path("source").get("sourceContextId"));
    var draft = (ObjectNode) preview.path("draft").deepCopy();
    draft.put("name", "Copied brief");
    body.set("draft", draft);
    return body;
  }

  void finish() throws Exception {
    ready();
    accept();
    request(brand, root + "/review", review(), 200);
  }

  @Test
  void mainReportUsesFinalReviewedAcceptedOutcomeAndExecutionKeepsSource() throws Exception {
    ready();
    accept();
    assertThat(read(history()).path("report").path("eligibleCount").asInt()).isZero();
    request(brand, root + "/review", review(), 200);
    var report = read(history()).path("report");
    assertThat(report.path("eligibleCount").asInt()).isEqualTo(1);
    assertThat(report.path("metrics").path("actualCost").path("value").decimalValue())
        .isEqualByComparingTo("1000");
    assertThat(report.path("metrics").path("roas").path("value").decimalValue())
        .isEqualByComparingTo("5");
    var detail = read(history() + "/collaborations/" + collaboration);
    assertThat(detail.path("agreedProposal").path("source").asText())
        .isEqualTo("CONFIRMED_PROPOSAL");
    assertThat(detail.path("agreedProposal").path("fee").decimalValue())
        .isEqualByComparingTo("10000");
    assertThat(detail.path("execution").path("review").path("acceptedOutcomeRevision").asInt())
        .isEqualTo(2);
    assertThat(detail.toString())
        .doesNotContain("rawToken", "token_hash", "encrypted_payload", "privateContact");
  }

  @Test
  void readsDoNotPrepareContextOrCreateDomainWrites() throws Exception {
    var before = counts();
    read(history());
    read(history() + "/contexts");
    read(history() + "/collaborations");
    read(history() + "/collaborations/" + collaboration);
    read(duplicate() + "/preview");
    assertThat(counts()).isEqualTo(before);
    assertThat(read(history()).path("snapshotAvailability").asText()).isEqualTo("MISSING");
  }

  List<Long> counts() {
    return List.of(
            "campaign_context",
            "campaign_embedding_job",
            "historical_campaign",
            "recommendation_run",
            "audit_log",
            "campaign_duplicate_request")
        .stream()
        .map(t -> jdbc.queryForObject("select count(*) from " + t, Long.class))
        .toList();
  }

  @Test
  void ownerRolesAndBothReadPermissionsAreEnforced() throws Exception {
    call(account(Role.BRAND), get(history()), null, 404);
    call(owner, get(history()), null, 403);
    call(account(Role.ADMIN), get(history()), null, 403);
    for (String permission : List.of("VIEW_OWN_HISTORY_AND_INSIGHTS", "VIEW_OWN_CAMPAIGN")) {
      jdbc.update(
          "insert into role_permission_override(id,role,permission,enabled)"
              + " values(gen_random_uuid(),'BRAND',?,false) on conflict(role,permission) do update"
              + " set enabled=false",
          permission);
      try {
        call(brand, get(history()), null, 403);
      } finally {
        jdbc.update(
            "delete from role_permission_override where role='BRAND' and permission=?", permission);
      }
    }
  }

  @Test
  void historyDoesNotRequireWritePermissionsAndWrongChildIs404() throws Exception {
    var permission = "MANAGE_OWN_COLLABORATION";
    jdbc.update(
        "insert into role_permission_override(id,role,permission,enabled)"
            + " values(gen_random_uuid(),'BRAND',?,false) on conflict(role,permission) do update"
            + " set enabled=false",
        permission);
    try {
      read(history() + "/collaborations/" + collaboration);
    } finally {
      jdbc.update(
          "delete from role_permission_override where role='BRAND' and permission=?", permission);
    }
    call(brand, get(history() + "/collaborations/" + UUID.randomUUID()), null, 404);
  }

  @Test
  void filtersUseCreatedDatesAndLiteralNameAndBoundPagination() throws Exception {
    jdbc.update(
        "update campaign set name='Only_% literal',created_at='2026-10-20 12:30:00' where id=?",
        campaign);
    var result =
        read(
            "/campaign-history?status=ACTIVE&platform=TIKTOK&createdFrom=2026-10-20&createdTo=2026-10-20&q=Only_&size=1");
    assertThat(result.path("totalElements").asLong()).isEqualTo(1);
    assertThat(result.path("items").get(0).path("id").asText()).isEqualTo(campaign.toString());
    assertThat(read("/campaign-history?createdFrom=2026-10-21").path("items").isEmpty()).isTrue();
    call(brand, get("/campaign-history?createdFrom=2026-10-21&createdTo=2026-10-20"), null, 400);
    call(brand, get("/campaign-history?size=101"), null, 400);
    call(brand, get("/campaign-history?page=-1"), null, 400);
  }

  UUID snapshot(int version, String data) {
    var id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " campaign_context(id,campaign_id,context_version,context_data,active,created_at,updated_at)"
            + " values(?,?,?,?::jsonb,false,now(),now())",
        id,
        campaign,
        version,
        data);
    return id;
  }

  @Test
  void historicalSnapshotNeverFillsFromCurrentRecordAndExplicitSelectionIsStable()
      throws Exception {
    var old =
        snapshot(
            1, "{\"name\":\"Frozen brief\",\"budgetMin\":200,\"snapshotKind\":\"ACTIVATION\"}");
    snapshot(2, "{\"name\":\"New brief\"}");
    jdbc.update("update campaign set name='Current name',total_budget=999 where id=?", campaign);
    var preview = read(duplicate() + "/preview?contextId=" + old);
    assertThat(preview.path("draft").path("name").asText()).isEqualTo("Frozen brief");
    assertThat(preview.path("draft").path("totalBudget").isNull()).isTrue();
    assertThat(preview.path("warnings").toString()).contains("LEGACY_BUDGET_NOT_CONVERTED");
    assertThat(read(history() + "/contexts?size=1").path("totalElements").asLong()).isEqualTo(2);
    assertThat(read(history()).path("latestContext").path("brief").path("name").asText())
        .isEqualTo("New brief");
    call(brand, get(duplicate() + "/preview?contextId=" + UUID.randomUUID()), null, 404);
  }

  @Test
  void duplicateIsDraftWithoutTimelineOrCooperationAndRetryDoesNotChargeAgain() throws Exception {
    var body = copyBody();
    var key = UUID.randomUUID().toString();
    var first = request(brand, duplicate(), body, 201, key);
    var target = UUID.fromString(first.path("campaign").path("id").asText());
    assertThat(first.path("campaign").path("status").asText()).isEqualTo("DRAFT");
    assertThat(
            jdbc.queryForMap("select start_date,end_date from campaign where id=?", target)
                .values())
        .containsOnlyNulls();
    for (String table :
        List.of("campaign_context", "relationship", "collaboration", "cooperation_deal"))
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + table + " where campaign_id=?", Long.class, target))
          .isZero();
    long quota =
        jdbc.queryForObject(
            "select used_campaign_quota from subscription where brand_profile_id=(select"
                + " brand_profile_id from campaign where id=?)",
            Long.class,
            campaign);
    assertThat(request(brand, duplicate(), body, 201, key)).isEqualTo(first);
    assertThat(
            jdbc.queryForObject(
                "select used_campaign_quota from subscription where brand_profile_id=(select"
                    + " brand_profile_id from campaign where id=?)",
                Long.class,
                campaign))
        .isEqualTo(quota);
    body.with("draft").put("name", "Different");
    request(brand, duplicate(), body, 409, key);
    assertThat(
            read("/campaigns/" + target + "/history")
                .path("copyOrigin")
                .path("sourceCampaignId")
                .asText())
        .isEqualTo(campaign.toString());
  }

  @Test
  void staleRevisionAndChangedSourceAreRejected() throws Exception {
    var body = copyBody();
    jdbc.update("update campaign set campaign_revision=campaign_revision+1 where id=?", campaign);
    request(brand, duplicate(), body, 409);
    body = copyBody();
    snapshot(1, "{\"name\":\"Now has context\"}");
    request(brand, duplicate(), body, 409);
  }

  @Test
  void auditFailureRollsBackDuplicateAndQuota() throws Exception {
    var body = copyBody();
    var before =
        jdbc.queryForObject("select sum(used_campaign_quota) from subscription", Long.class);
    doThrow(new IllegalStateException("audit unavailable"))
        .when(
            (com.influencermatch.backend.audit.service.AuditService)
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit))
        .record(eq("DUPLICATE_CAMPAIGN_BRIEF"), anyString(), any(), any(), any(), anyString());
    request(brand, duplicate(), body, 500);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from campaign_duplicate_request where source_campaign_id=?",
                Long.class,
                campaign))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from campaign where name='Copied brief' and"
                    + " brand_profile_id=(select brand_profile_id from campaign where id=?)",
                Long.class,
                campaign))
        .isZero();
    assertThat(jdbc.queryForObject("select sum(used_campaign_quota) from subscription", Long.class))
        .isEqualTo(before);
  }

  @Test
  void concurrentRetryCreatesExactlyOneTargetAndReceipt() throws Exception {
    var body = copyBody();
    var key = UUID.randomUUID().toString();
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<JsonNode> action =
          () -> {
            gate.await();
            return request(brand, duplicate(), body, 201, key);
          };
      var one = pool.submit(action);
      var two = pool.submit(action);
      gate.countDown();
      assertThat(one.get(30, TimeUnit.SECONDS)).isEqualTo(two.get(30, TimeUnit.SECONDS));
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from campaign_duplicate_request where actor_id=? and"
                      + " request_key=?",
                  Long.class,
                  brand.getId(),
                  key))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void reopenedOutcomeNeverEntersCompletedReportAndLegacyIsLabelled() throws Exception {
    ready();
    accept();
    request(
        brand,
        root + "/outcome/reopen",
        Map.of("expectedRevision", revision(), "reason", "Need corrected evidence"),
        200);
    var report = read(history()).path("report");
    assertThat(report.path("eligibleCount").asInt()).isZero();
    jdbc.update(
        "update collaboration set status='COMPLETED',execution_source='LEGACY' where id=?",
        collaboration);
    var detail = read(history() + "/collaborations/" + collaboration);
    assertThat(detail.path("collaboration").path("provenance").asText())
        .isEqualTo("LEGACY_UNVERIFIED");
    assertThat(
            read(history())
                .path("report")
                .path("metrics")
                .path("actualCost")
                .path("value")
                .isNull())
        .isTrue();
  }

  @Test
  void zeroAndLargeRevisionWorkButMissingAndFractionalRevisionAreRejected() throws Exception {
    var body = copyBody();
    body.remove("expectedSourceRevision");
    request(brand, duplicate(), body, 400);
    body.put("expectedSourceRevision", 0.5);
    request(brand, duplicate(), body, 400);
    jdbc.update("update campaign set campaign_revision=257 where id=?", campaign);
    body = copyBody();
    assertThat(body.path("expectedSourceRevision").asLong()).isEqualTo(257);
    request(brand, duplicate(), body, 201);
  }

  @Test
  void currentProfilesAndLaterContextsCannotRewriteAcceptedEvidence() throws Exception {
    finish();
    var before = read(history() + "/collaborations/" + collaboration);
    jdbc.update(
        "update creator set user_name='changed-handle',display_name='Current name' where id=?",
        channel);
    snapshot(1, "{\"name\":\"A later context\"}");
    var after = read(history() + "/collaborations/" + collaboration);
    assertThat(after.path("agreedProposal")).isEqualTo(before.path("agreedProposal"));
    assertThat(after.path("execution").path("outcomeRevisions"))
        .isEqualTo(before.path("execution").path("outcomeRevisions"));
    assertThat(after.path("collaboration").path("currentChannels").get(0).path("username").asText())
        .isEqualTo("changed-handle");
    assertThat(after.path("currentProfileSource").asText()).isEqualTo("CURRENT_PROFILE");
  }

  @Test
  void previewRequiresReadPermissionsButCreatingRequiresCreatePermission() throws Exception {
    var body = copyBody();
    jdbc.update(
        "insert into role_permission_override(id,role,permission,enabled)"
            + " values(gen_random_uuid(),'BRAND','CREATE_CAMPAIGN',false) on"
            + " conflict(role,permission) do update set enabled=false");
    try {
      read(duplicate() + "/preview");
      read(history());
      request(brand, duplicate(), body, 403);
    } finally {
      jdbc.update(
          "delete from role_permission_override where role='BRAND' and"
              + " permission='CREATE_CAMPAIGN'");
    }
  }

  @Test
  void oneHistoryRequestRetainsTheSameDatabaseSnapshotDuringConcurrentUpdate() throws Exception {
    var entered = new CountDownLatch(1);
    var continueRead = new CountDownLatch(1);
    var target =
        (com.influencermatch.backend.campaign.service.CampaignService)
            org.springframework.test.util.AopTestUtils.getUltimateTargetObject(campaignService);
    doAnswer(
            invocation -> {
              entered.countDown();
              if (!continueRead.await(30, TimeUnit.SECONDS))
                throw new IllegalStateException("Test gate timed out");
              return invocation.callRealMethod();
            })
        .when(target)
        .get(eq(campaign), any());
    var pool = Executors.newSingleThreadExecutor();
    try {
      var response = pool.submit(() -> read(history()));
      assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
      jdbc.update("update campaign set name='Concurrent change' where id=?", campaign);
      snapshot(1, "{}");
      continueRead.countDown();
      var result = response.get(30, TimeUnit.SECONDS);
      assertThat(result.path("currentCampaign").path("name").asText()).isEqualTo("Test Campaign");
      assertThat(result.path("snapshotAvailability").asText()).isEqualTo("MISSING");
    } finally {
      continueRead.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void multiChannelDealCountsOneCommitmentAndOneActualCost() throws Exception {
    UUID brandId =
        jdbc.queryForObject(
            "select brand_profile_id from campaign where id=?", UUID.class, campaign);
    UUID otherCampaign = createCampaign(brand, brandId);
    jdbc.update(
        "update campaign set"
            + " status='ACTIVE',platforms=ARRAY['TIKTOK','INSTAGRAM'],creator_mode='SINGLE',target_creator_count=1,total_budget=1000000,currency='VND'"
            + " where id=?",
        otherCampaign);
    UUID masterId =
        jdbc.queryForObject("select master_id from creator where id=?", UUID.class, channel);
    var second =
        creators
            .saveAndFlush(
                com.influencermatch.backend.creator.model.Creator.builder()
                    .platform("INSTAGRAM")
                    .externalId(UUID.randomUUID().toString())
                    .userName("instagram-test")
                    .sourceType(
                        com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED)
                    .authorizedAt(java.time.LocalDateTime.now())
                    .master(masters.findById(masterId).orElseThrow())
                    .build())
            .getId();
    verifyChannel(second, owner.getId(), "INSTAGRAM_OAUTH");
    var relationshipIds = new ArrayList<String>();
    for (UUID creator : List.of(channel, second))
      relationshipIds.add(
          call(
                  brand,
                  post("/campaigns/" + otherCampaign + "/relationships"),
                  Map.of("creatorId", creator),
                  200)
              .path("data")
              .path("id")
              .asText());
    var d =
        request(
                brand,
                "/campaigns/" + otherCampaign + "/deals",
                Map.of("relationshipIds", relationshipIds),
                200)
            .path("id")
            .asText();
    var base = "/campaigns/" + otherCampaign + "/deals/" + d;
    var terms =
        List.of(channel, second).stream()
            .map(
                id ->
                    Map.of(
                        "creatorId",
                        id,
                        "contentType",
                        "Review",
                        "quantity",
                        1,
                        "dueAt",
                        "2027-01-01T00:00:00Z",
                        "acceptanceChecklist",
                        List.of("Brand safe"),
                        "maxRevisionRounds",
                        1))
            .toList();
    request(
        brand,
        base + "/proposals",
        Map.of(
            "expectedRevision",
            1,
            "fee",
            10000,
            "currency",
            "VND",
            "terms",
            "One multi-channel agreement",
            "deliverables",
            terms),
        200);
    var confirmed =
        request(
            brand,
            base + "/confirm",
            Map.of(
                "expectedRevision",
                2,
                "agreementSource",
                "EXTERNAL",
                "agreedAt",
                java.time.Instant.now().minusSeconds(60).toString(),
                "evidenceSource",
                "EMAIL",
                "evidenceUrl",
                "https://evidence.test/multi",
                "evidenceDescription",
                "Agreement for both channels"),
            200);
    String execution = "/collaborations/" + confirmed.path("collaborationId").asText();
    request(brand, base + "/start", Map.of("expectedRevision", 3), 200);
    var units = read(execution + "/deliverables").path("deliverables");
    var posts = new ArrayList<Map<String, Object>>();
    int index = 1;
    for (var unit : units) {
      String id = unit.path("id").asText(), path = execution + "/deliverables/" + id;
      request(
          owner,
          path + "/submissions",
          Map.of(
              "expectedRevision",
              1,
              "url",
              "https://content.test/draft",
              "source",
              "CREATOR_PORTAL"),
          200);
      request(
          brand,
          path + "/review",
          Map.of(
              "expectedRevision",
              2,
              "submissionRevision",
              1,
              "decision",
              "APPROVE",
              "completedChecklist",
              List.of("Brand safe")),
          200);
      String postId = String.valueOf(index++);
      String published =
          unit.path("creatorId").asText().equals(channel.toString())
              ? "https://www.tiktok.com/@test/video/" + postId
              : "https://www.instagram.com/p/" + postId + "/";
      request(
          owner,
          path + "/publication",
          Map.of(
              "expectedRevision",
              3,
              "submissionRevision",
              1,
              "url",
              published,
              "postId",
              postId,
              "acknowledgeException",
              false),
          200);
      posts.add(
          Map.of(
              "deliverableId",
              id,
              "submissionRevision",
              1,
              "periodStart",
              "2026-11-01T00:00:00Z",
              "periodEnd",
              "2026-11-02T00:00:00Z",
              "views",
              1000,
              "unavailableMetrics",
              List.of("clicks"),
              "likes",
              20,
              "comments",
              2,
              "shares",
              3));
    }
    var data = new HashMap<String, Object>();
    data.put(
        "expectedRevision", read(execution + "/deliverables").path("executionRevision").asLong());
    data.put("reason", "Full multi-channel result");
    var kpi = new HashMap<>(data);
    kpi.put("posts", posts);
    request(owner, execution + "/outcome", kpi, 200);
    data.put("expectedRevision", read(execution + "/deliverables").path("executionRevision").asLong());
    data.put("costs", List.of(Map.of("description", "Total actual cost", "amount", 1000)));
    data.put("currency", "VND");
    data.put("financialConfirmed", true);
    data.put("periodStart", "2026-11-01T00:00:00Z");
    data.put("periodEnd", "2026-11-02T00:00:00Z");
    request(brand, execution + "/outcome/finance", data, 200);
    request(
        brand,
        execution + "/outcome/accept",
        Map.of(
            "expectedRevision",
            read(execution + "/deliverables").path("executionRevision").asLong(),
            "outcomeRevision",
            2),
        200);
    request(
        brand,
        execution + "/review",
        Map.of(
            "expectedRevision",
            read(execution + "/deliverables").path("executionRevision").asLong(),
            "outcomeRevision",
            2,
            "overallRating",
            5,
            "brandFitRating",
            5,
            "wouldCollaborateAgain",
            true,
            "reviewTags",
            List.of("on-time")),
        200);
    var report = read("/campaigns/" + otherCampaign + "/history").path("report");
    assertThat(report.path("completedCount").asInt()).isEqualTo(1);
    assertThat(report.path("eligibleCount").asInt()).isEqualTo(1);
    assertThat(report.path("metrics").path("committedFee").path("value").decimalValue())
        .isEqualByComparingTo("10000");
    assertThat(report.path("metrics").path("actualCost").path("value").decimalValue())
        .isEqualByComparingTo("1000");
    assertThat(report.path("metrics").path("views").path("value").decimalValue())
        .isEqualByComparingTo("2000");
    assertThat(
            read("/campaigns/" + otherCampaign + "/history/collaborations")
                .path("items")
                .get(0)
                .path("currentChannels")
                .size())
        .isEqualTo(2);
  }
}
