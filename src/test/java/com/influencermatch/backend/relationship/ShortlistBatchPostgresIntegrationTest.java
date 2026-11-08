package com.influencermatch.backend.relationship;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorIngestionService;
import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.service.*;
import com.influencermatch.backend.support.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

class ShortlistBatchPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired CreatorRepository creators;
  @Autowired CreatorMasterRepository masters;
  @Autowired PublicCreatorMetricRepository metrics;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;
  @Autowired org.springframework.context.ApplicationEventPublisher events;
  @Autowired CreatorIngestionService ingestion;
  @SpyBean ShortlistNotificationService notifications;

  @org.springframework.boot.test.mock.mockito.MockBean
  com.influencermatch.backend.creator.service.CreatorInvitationService invitations;

  User owner;
  UUID campaign;

  @BeforeEach
  void setup() throws Exception {
    owner = account(Role.BRAND);
    campaign = createCampaign(owner, createBrand(owner));
  }

  Creator channel() {
    return creators.saveAndFlush(
        Creator.builder()
            .platform("TIKTOK")
            .externalId(UUID.randomUUID().toString())
            .userName("channel" + UUID.randomUUID().toString().replace("-", ""))
            .displayName("Channel")
            .build());
  }

  UUID add(Creator c) throws Exception {
    return UUID.fromString(
        call(
                owner,
                post("/campaigns/" + campaign + "/shortlist"),
                Map.of("creatorId", c.getId()),
                200)
            .path("data")
            .path("id")
            .asText());
  }

  long count() {
    return jdbc.queryForObject(
        "select count(*) from shortlist_item where campaign_id=?", Long.class, campaign);
  }

  PublicCreatorMetric metric(Creator c, LocalDateTime time, long followers) {
    return metrics.saveAndFlush(
        PublicCreatorMetric.builder()
            .creator(c)
            .collectedAt(time)
            .followers(followers)
            .niche("Beauty")
            .freshnessStatus(com.influencermatch.backend.creator.enums.MetricDataStatus.FRESH)
            .dataConfidence(new BigDecimal("0.9"))
            .contact("booking@example.test")
            .build());
  }

  @Test
  void batchPreservesOrderAndCommitsValidItemsAroundInvalidOne() throws Exception {
    var a = channel();
    var b = channel();
    UUID invalid = UUID.randomUUID();
    var result =
        call(
                owner,
                post("/campaigns/" + campaign + "/shortlist/batch"),
                Map.of("creatorIds", List.of(a.getId(), invalid, b.getId())),
                200)
            .path("data");
    assertThat(result.path("results").get(0).path("action").asText()).isEqualTo("ADDED");
    assertThat(result.path("results").get(1).path("action").asText()).isEqualTo("INVALID");
    assertThat(result.path("results").get(2).path("creatorId").asText())
        .isEqualTo(b.getId().toString());
    assertThat(count()).isEqualTo(2);
    assertThat(result.path("counts").path("ADDED").asInt()).isEqualTo(2);
  }

  @Test
  void duplicateNullAndAliasMismatchNeverWrite() throws Exception {
    var c = channel();
    for (var body :
        List.of(
            Map.of("creatorIds", List.of()), Map.of("creatorIds", List.of(c.getId(), c.getId()))))
      call(owner, post("/campaigns/" + campaign + "/shortlist/batch"), body, 400);
    var node = json.createObjectNode();
    node.putArray("creatorIds").addNull();
    call(owner, post("/campaigns/" + campaign + "/shortlist/batch"), node, 400);
    call(
        owner,
        post("/campaigns/" + campaign + "/shortlist"),
        Map.of("creatorId", c.getId(), "creatorAccountId", UUID.randomUUID()),
        400);
    assertThat(count()).isZero();
  }

  @Test
  void duplicateAddDoesNotOverwriteMetadataAndRestorePreservesIdentity() throws Exception {
    var beforeAdd = LocalDateTime.now().minusSeconds(2);
    var c = channel();
    UUID id =
        UUID.fromString(
            call(
                    owner,
                    post("/campaigns/" + campaign + "/shortlist"),
                    Map.of("creatorId", c.getId(), "note", "Original", "priority", "LOW"),
                    200)
                .path("data")
                .path("id")
                .asText());
    var addedRow =
        call(owner, get("/campaigns/" + campaign + "/shortlist"), null, 200)
            .path("data")
            .path("content")
            .get(0);
    assertThat(json.treeToValue(addedRow.path("addedAt"), LocalDateTime.class))
        .isBetween(beforeAdd, LocalDateTime.now().plusSeconds(2));
    String added =
        jdbc.queryForObject(
            "select added_at::text from shortlist_item where id=?", String.class, id);
    var existing =
        call(
                owner,
                post("/campaigns/" + campaign + "/shortlist"),
                Map.of("creatorId", c.getId(), "note", "Changed", "priority", "HIGH"),
                200)
            .path("data");
    assertThat(existing.path("note").asText()).isEqualTo("Original");
    assertThat(existing.path("priority").asText()).isEqualTo("LOW");
    assertThat(existing.path("refreshRequestStatus").asText()).isEqualTo("NOT_REQUESTED");
    call(owner, delete("/campaigns/" + campaign + "/shortlist/" + id), null, 200);
    var restored =
        call(
                owner,
                post("/campaigns/" + campaign + "/shortlist/items"),
                Map.of("creatorAccountId", c.getId(), "note", "Restored"),
                200)
            .path("data");
    assertThat(restored.path("action").asText()).isEqualTo("RESTORED");
    assertThat(restored.path("id").asText()).isEqualTo(id.toString());
    assertThat(restored.path("priority").asText()).isEqualTo("LOW");
    assertThat(
            jdbc.queryForObject(
                "select added_at::text from shortlist_item where id=?", String.class, id))
        .isEqualTo(added);
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void simultaneousAddsOnlyCreateOneItem() throws Exception {
    var c = channel();
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  return call(
                          owner,
                          post("/campaigns/" + campaign + "/shortlist"),
                          Map.of("creatorId", c.getId()),
                          200)
                      .path("data")
                      .path("action")
                      .asText();
                }));
      gate.countDown();
      assertThat(
              List.of(
                  results.get(0).get(20, TimeUnit.SECONDS),
                  results.get(1).get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("ADDED", "ALREADY_EXISTS");
      assertThat(count()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void failedItemRollsBackWithoutLosingOtherBatchItems() throws Exception {
    var a = channel();
    var bad = channel();
    var c = channel();
    jdbc.execute(
        "CREATE FUNCTION shortlist_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.creator_id='"
            + bad.getId()
            + "'::uuid THEN RAISE EXCEPTION 'sensitive internal test detail'; END IF; RETURN NEW;"
            + " END $$");
    jdbc.execute(
        "CREATE TRIGGER shortlist_test_failure BEFORE INSERT ON shortlist_item FOR EACH ROW EXECUTE"
            + " FUNCTION shortlist_test_failure()");
    try {
      var result =
          call(
                  owner,
                  post("/campaigns/" + campaign + "/shortlist/batch"),
                  Map.of("creatorIds", List.of(a.getId(), bad.getId(), c.getId())),
                  200)
              .path("data");
      assertThat(result.path("results").get(1).path("action").asText()).isEqualTo("FAILED");
      assertThat(result.toString()).doesNotContain("sensitive internal");
      assertThat(count()).isEqualTo(2);
    } finally {
      jdbc.execute("DROP TRIGGER shortlist_test_failure ON shortlist_item");
      jdbc.execute("DROP FUNCTION shortlist_test_failure()");
    }
  }

  @Test
  void invalidRecommendationRunIsNotSilentlyIgnored() throws Exception {
    var c = channel();
    call(
        owner,
        post("/campaigns/" + campaign + "/shortlist"),
        Map.of("creatorId", c.getId(), "recommendationRunId", UUID.randomUUID()),
        400);
    assertThat(count()).isZero();
    var other = account(Role.BRAND);
    UUID otherCampaign = createCampaign(other, createBrand(other));
    UUID otherBrand =
        jdbc.queryForObject(
            "select brand_profile_id from campaign where id=?", UUID.class, otherCampaign);
    UUID run = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " recommendation_run(id,campaign_id,brand_profile_id,status,candidates_count,created_at,context_version)"
            + " values(?,?,?,'COMPLETED',1,now(),1)",
        run,
        otherCampaign,
        otherBrand);
    call(
        owner,
        post("/campaigns/" + campaign + "/shortlist"),
        Map.of("creatorId", c.getId(), "recommendationRunId", run),
        400);
    assertThat(count()).isZero();
  }

  @Test
  void compareExcludesRemovedAndReadsLatestSnapshotWithTieBreaker() throws Exception {
    var a = channel();
    var b = channel();
    UUID item = add(a);
    add(b);
    var time = LocalDateTime.of(2026, 11, 8, 0, 0);
    var old = metric(a, time.minusDays(1), 10);
    var x = metric(a, time, 20);
    var y = metric(a, time, 30);
    var latest = metrics.latestForCreators(List.of(a.getId())).getFirst();
    var response =
        call(
                owner,
                post("/campaigns/" + campaign + "/shortlist/compare"),
                Map.of("creatorIds", List.of(b.getId(), a.getId())),
                200)
            .path("data")
            .path("creators");
    assertThat(response.get(0).path("metrics").isNull()).isTrue();
    assertThat(response.get(1).path("metrics").path("metricId").asText())
        .isEqualTo(latest.getId().toString());
    assertThat(response.get(1).path("feeAssessmentStatus").asText()).isEqualTo("UNAVAILABLE");
    assertThat(response.toString()).doesNotContain("booking@example.test", "rateCard", "quotedFee");
    var page =
        call(owner, get("/campaigns/" + campaign + "/shortlist"), null, 200)
            .path("data")
            .path("content");
    var row =
        java.util.stream.StreamSupport.stream(page.spliterator(), false)
            .filter(n -> n.path("creatorId").asText().equals(a.getId().toString()))
            .findFirst()
            .orElseThrow();
    assertThat(row.path("metricId").asText()).isEqualTo(latest.getId().toString());
    call(owner, delete("/campaigns/" + campaign + "/shortlist/" + item), null, 200);
    call(
        owner,
        post("/campaigns/" + campaign + "/shortlist/compare"),
        Map.of("creatorIds", List.of(a.getId(), b.getId())),
        400);
  }

  @Test
  void multipleChannelsOfOneMasterRemainSeparateWithoutBooking() throws Exception {
    var master =
        masters.saveAndFlush(
            CreatorMaster.builder()
                .user(account(Role.CREATOR))
                .canonicalName("Same Creator")
                .build());
    var a = channel();
    var b = channel();
    a.setMaster(master);
    b.setMaster(master);
    a.setSourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED);
    b.setSourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED);
    a.setAuthorizedAt(LocalDateTime.now());
    b.setAuthorizedAt(LocalDateTime.now());
    creators.saveAndFlush(a);
    creators.saveAndFlush(b);
    call(
        owner,
        post("/campaigns/" + campaign + "/shortlist/batch"),
        Map.of("creatorIds", List.of(a.getId(), b.getId())),
        200);
    var result =
        call(
                owner,
                post("/campaigns/" + campaign + "/shortlist/compare"),
                Map.of("creatorIds", List.of(a.getId(), b.getId())),
                200)
            .path("data")
            .path("creators");
    assertThat(result.size()).isEqualTo(2);
    for (var row : result)
      assertThat(row.path("masterId").asText()).isEqualTo(master.getId().toString());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from relationship where campaign_id=?", Integer.class, campaign))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration where campaign_id=?", Integer.class, campaign))
        .isZero();
  }

  @Test
  void crossBrandSseBatchAndCompareAreRejected() throws Exception {
    var c = channel();
    var d = channel();
    add(c);
    add(d);
    var other = account(Role.BRAND);
    call(
        other,
        post("/campaigns/" + campaign + "/shortlist/batch"),
        Map.of("creatorIds", List.of(c.getId())),
        404);
    call(other, get("/campaigns/" + campaign + "/shortlist/events"), null, 404);
    call(
        other,
        post("/campaigns/" + campaign + "/shortlist/compare"),
        Map.of("creatorIds", List.of(c.getId(), d.getId())),
        404);
  }

  @Test
  void crawlAcceptanceAndFailureNeverRollBackSavedShortlist() throws Exception {
    var c = channel();
    var gate = new CountDownLatch(1);
    doAnswer(
            i -> {
              var event = (ShortlistCrawlEvent) i.getArgument(1);
              if (c.getId().equals(event.getCreatorId())) {
                assertThat(event.getEventType()).isEqualTo("CRAWL_REQUEST_FAILED");
                assertThat(event.getMessage()).doesNotContain("SECRET");
                gate.countDown();
              }
              return null;
            })
        .when(notifications)
        .broadcast(any(), any());
    doThrow(new IllegalStateException("SECRET provider body"))
        .when(crawler)
        .requestShortlistCrawl(anyString(), anyString(), eq(c.getId()), any());
    var added =
        call(
                owner,
                post("/campaigns/" + campaign + "/shortlist"),
                Map.of("creatorId", c.getId()),
                200)
            .path("data");
    assertThat(added.path("refreshRequestStatus").asText()).isEqualTo("PENDING_DISPATCH");
    assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(count()).isEqualTo(1);
    call(owner, post("/campaigns/" + campaign + "/shortlist"), Map.of("creatorId", c.getId()), 200);
    verify(crawler, times(1)).requestShortlistCrawl(anyString(), anyString(), eq(c.getId()), any());
  }

  @Test
  void refreshDispatchOnlyRunsAfterCommitAndRollbackDoesNotDispatch() throws Exception {
    var c = channel();
    var rollback = channel();
    var gate = new CountDownLatch(1);
    doAnswer(
            i -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              gate.countDown();
              return null;
            })
        .when(crawler)
        .requestShortlistCrawl(anyString(), anyString(), eq(c.getId()), any());
    tx.executeWithoutResult(
        s -> {
          events.publishEvent(
              new ShortlistRefreshEvents.Requested(
                  campaign, c.getId(), "TIKTOK", "test", "Public"));
          verify(crawler, never())
              .requestShortlistCrawl(anyString(), anyString(), eq(c.getId()), any());
        });
    assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
    tx.executeWithoutResult(
        s -> {
          events.publishEvent(
              new ShortlistRefreshEvents.Requested(
                  campaign, rollback.getId(), "TIKTOK", "rollback", "Public"));
          s.setRollbackOnly();
        });
    verify(crawler, never())
        .requestShortlistCrawl(anyString(), anyString(), eq(rollback.getId()), any());
  }

  @Test
  void readinessAndOutreachUseLatestContactSnapshot() throws Exception {
    var c = channel();
    UUID item = add(c);
    var old = metric(c, LocalDateTime.of(2026, 1, 1, 0, 0), 10000);
    old.setContact("old@example.test");
    metrics.saveAndFlush(old);
    var fresh = metric(c, LocalDateTime.of(2026, 11, 8, 0, 0), 20000);
    fresh.setContact("new@example.test");
    metrics.saveAndFlush(fresh);
    var readiness =
        call(
                owner,
                post("/campaigns/" + campaign + "/shortlist/contact-readiness"),
                Map.of("creatorIds", List.of(c.getId())),
                200)
            .path("data");
    assertThat(readiness.toString())
        .contains("new@example.test")
        .doesNotContain("old@example.test");
    when(invitations.sendInvitation(eq(c.getId()), eq("new@example.test"), any(), eq(campaign)))
        .thenReturn(
            com.influencermatch.backend.creator.dto.InvitationResponse.builder()
                .id(UUID.randomUUID())
                .build());
    call(
        owner,
        post("/campaigns/" + campaign + "/shortlist/outreach"),
        Map.of("shortlistItemIds", List.of(item)),
        200);
    verify(invitations).sendInvitation(eq(c.getId()), eq("new@example.test"), any(), eq(campaign));
  }

  @Test
  void permissionDisabledBlocksAddCompareAndSseWithSameToken() throws Exception {
    var c = channel();
    var d = channel();
    add(c);
    add(d);
    String token = jwt.generateToken(owner);
    UUID override = UUID.randomUUID();
    jdbc.update(
        "insert into role_permission_override(id,role,permission,enabled)"
            + " values(?,'BRAND','MANAGE_OWN_SHORTLIST',false)",
        override);
    try {
      callToken(
          token,
          post("/campaigns/" + campaign + "/shortlist/batch"),
          Map.of("creatorIds", List.of(c.getId())),
          403);
      callToken(
          token,
          post("/campaigns/" + campaign + "/shortlist/compare"),
          Map.of("creatorIds", List.of(c.getId(), d.getId())),
          403);
      callToken(token, get("/campaigns/" + campaign + "/shortlist/events"), null, 403);
    } finally {
      jdbc.update("delete from role_permission_override where id=?", override);
    }
  }

  @Test
  void concurrentRemoveAndAddStayConsistentAndUnique() throws Exception {
    var c = channel();
    UUID item = add(c);
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var remove =
          pool.submit(
              () -> {
                gate.await();
                call(owner, delete("/campaigns/" + campaign + "/shortlist/" + item), null, 200);
                return true;
              });
      var add =
          pool.submit(
              () -> {
                gate.await();
                return call(
                        owner,
                        post("/campaigns/" + campaign + "/shortlist"),
                        Map.of("creatorId", c.getId()),
                        200)
                    .path("data")
                    .path("action")
                    .asText();
              });
      gate.countDown();
      assertThat(remove.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(add.get(20, TimeUnit.SECONDS)).isIn("ALREADY_EXISTS", "RESTORED");
      assertThat(count()).isEqualTo(1);
      var last =
          call(
                  owner,
                  post("/campaigns/" + campaign + "/shortlist"),
                  Map.of("creatorId", c.getId()),
                  200)
              .path("data");
      assertThat(last.path("id").asText()).isEqualTo(item.toString());
      assertThat(last.path("status").asText()).isEqualTo("SAVED");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void ingestionCompletionOnlyPublishesCommittedMetric() throws Exception {
    var c = channel();
    add(c);
    var received = new java.util.concurrent.atomic.AtomicReference<ShortlistCrawlEvent>();
    doAnswer(
            i -> {
              var event = (ShortlistCrawlEvent) i.getArgument(1);
              if (c.getId().equals(event.getCreatorId())
                  && "CRAWL_COMPLETED".equals(event.getEventType())) received.set(event);
              return null;
            })
        .when(notifications)
        .broadcast(any(), any());
    var request =
        CreatorIngestionRequest.builder()
            .platform("TIKTOK")
            .externalId(c.getExternalId())
            .userName(c.getUserName())
            .creatorType("KOC")
            .followers(12000L)
            .dataConfidence(new BigDecimal("0.9"))
            .contact("PRIVATE_CONTACT")
            .build();
    var auth =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            owner,
            null,
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "SERVICE_INGEST_CREATOR")));
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(auth);
    try {
      tx.executeWithoutResult(
          status -> {
            ingestion.ingestCreatorData(request);
            assertThat(received.get()).isNull();
            status.setRollbackOnly();
          });
      assertThat(received.get()).isNull();
      var result = tx.execute(status -> ingestion.ingestCreatorData(request));
      assertThat(received.get()).isNotNull();
      assertThat(received.get().getMetricId()).isEqualTo(result.getMetricId());
      assertThat(received.get().getCollectedAt())
          .isEqualTo(metrics.findById(result.getMetricId()).orElseThrow().getCollectedAt());
      assertThat(json.writeValueAsString(received.get())).doesNotContain("PRIVATE_CONTACT");
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }
}
