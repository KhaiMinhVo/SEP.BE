package com.influencermatch.backend.creator.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.portal.CreatorPortalProjection;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.ManualClaimGuard;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

class CreatorCatalogPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired java.time.Clock clock;
  @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
  @Autowired CreatorRepository creators;
  @Autowired CreatorMasterRepository masters;
  @Autowired CreatorInvitationRepository invitations;
  @Autowired CreatorClaimRepository claims;
  @Autowired CreatorPortalProjection projection;
  @SpyBean AuditService audit;
  @SpyBean ManualClaimGuard guard;
  User dm, owner;
  Creator channel;

  @BeforeEach
  void fixture() {
    dm = account(Role.DATA_MANAGER);
    owner = account(Role.CREATOR);
    channel = channel("TIKTOK", UUID.randomUUID().toString());
  }

  Creator channel(String platform, String name) {
    return creators.saveAndFlush(
        Creator.builder()
            .platform(platform)
            .externalId(UUID.randomUUID().toString())
            .userName(name)
            .build());
  }

  UUID submit(User actor, Creator c) throws Exception {
    return UUID.fromString(
        call(
                actor,
                post("/creators/" + c.getId() + "/claim"),
                Map.of(
                    "evidenceUrls",
                    List.of("https://proof.test/private"),
                    "note",
                    "Private evidence"),
                201)
            .at("/data/id")
            .asText());
  }

  JsonNode approve(UUID id, int status) throws Exception {
    return call(
        dm,
        patch("/data/creator-claims/" + id + "/review"),
        Map.of("decision", "APPROVED", "reason", "Checked evidence"),
        status);
  }

  JsonNode revoke(UUID id, int status) throws Exception {
    return call(
        dm,
        post("/data/creator-claims/" + id + "/revoke"),
        Map.of("reason", "Withdraw manual evidence"),
        status);
  }

  CreatorInvitation invite(User actor, LocalDateTime expires) {
    return invitations.saveAndFlush(
        CreatorInvitation.builder()
            .targetCreator(channel)
            .invitedBy(dm)
            .invitedEmail(actor.getEmail())
            .tokenHash(
                UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", ""))
            .expiresAt(expires)
            .build());
  }

  void proof(String method, String status, LocalDateTime at) {
    jdbc.update(
        "insert into"
            + " creator_account_verification(id,creator_id,verified_by_user_id,verification_method,status,verified_at,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        channel.getId(),
        owner.getId(),
        method,
        status,
        at,
        at,
        at);
  }

  long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  @Test
  void catalogIncludesEmptyMasterAndLiteralSearchHasNoWildcard() throws Exception {
    var empty =
        masters.saveAndFlush(
            CreatorMaster.builder()
                .user(owner)
                .canonicalName("Literal_%" + UUID.randomUUID())
                .build());
    long before = count("audit_log");
    var r =
        call(dm, get("/data/creator-masters").param("q", empty.getCanonicalName()), null, 200)
            .path("data");
    assertThat(r.path("items").size()).isEqualTo(1);
    assertThat(r.path("items").get(0).path("accountCount").asLong()).isZero();
    call(dm, get("/data/creator-masters/" + empty.getId() + "/accounts"), null, 200);
    var text =
        call(account(Role.ADMIN), get("/data/creator-masters/" + empty.getId()), null, 200)
            .toString();
    assertThat(text).doesNotContain(owner.getEmail(), "notes", "contacts", "token");
    assertThat(
            call(
                    dm,
                    get("/data/creator-masters")
                        .param("q", empty.getId().toString().toUpperCase(java.util.Locale.ROOT)),
                    null,
                    200)
                .at("/data/totalElements")
                .asLong())
        .isEqualTo(1);
    assertThat(count("audit_log")).isEqualTo(before);
    call(dm, get("/data/creator-masters/" + UUID.randomUUID()), null, 404);
  }

  @Test
  void unclaimedAndValidationAndRoles() throws Exception {
    var r =
        call(
                dm,
                get("/data/creator-accounts/unclaimed")
                    .param("q", channel.getUserName())
                    .param("platform", "TIKTOK"),
                null,
                200)
            .path("data");
    assertThat(r.path("items").size()).isEqualTo(1);
    assertThat(r.path("items").get(0).at("/verification/status").asText()).isEqualTo("UNVERIFIED");
    for (var role : List.of(Role.BRAND, Role.CREATOR))
      call(account(role), get("/data/creator-masters"), null, 403);
    call(dm, get("/data/creator-masters").param("size", "101"), null, 400);
    call(dm, get("/data/creator-masters").param("page", "-1"), null, 400);
    call(dm, get("/data/creator-masters").param("platform", "BAD"), null, 400);
    call(dm, get("/data/creator-masters").param("verificationStatus", "BAD"), null, 400);
    call(dm, get("/data/creator-masters").param("q", "x".repeat(201)), null, 400);
  }

  @Test
  void disabledPermissionAndReadHistoryPermissionAreIndependent() throws Exception {
    jdbc.update(
        "insert into role_permission_override(id,role,permission,enabled)"
            + " values(gen_random_uuid(),'DATA_MANAGER','VIEW_CREATOR_MASTER_CATALOG',false) on"
            + " conflict(role,permission) do update set enabled=false");
    try {
      call(dm, get("/data/creator-masters"), null, 403);
      call(dm, get("/data/creator-identity-events"), null, 200);
    } finally {
      jdbc.update(
          "delete from role_permission_override where role='DATA_MANAGER' and"
              + " permission='VIEW_CREATOR_MASTER_CATALOG'");
    }
  }

  @Test
  void approvalBindsInvitationAndConsumedOrExpiredCannotBeApproved() throws Exception {
    var i = invite(owner, LocalDateTime.now(clock).plusDays(1));
    UUID id = submit(owner, channel);
    assertThat(claims.findById(id).orElseThrow().getInvitation().getId()).isEqualTo(i.getId());
    i.setExpiresAt(LocalDateTime.now(clock).minusSeconds(1));
    invitations.saveAndFlush(i);
    assertThat(approve(id, 409).path("code").asText()).isEqualTo("INVITATION_EXPIRED");
    assertThat(creators.findById(channel.getId()).orElseThrow().getMaster()).isNull();
    i.setExpiresAt(LocalDateTime.now(clock).plusDays(1));
    i.setStatus("ACCEPTED");
    invitations.saveAndFlush(i);
    assertThat(approve(id, 409).path("code").asText()).isEqualTo("INVITATION_ALREADY_PROCESSED");
  }

  @Test
  void invitationEmailCannotBeBypassedAtSubmissionOrReview() throws Exception {
    var wrong = account(Role.CREATOR);
    var i = invite(owner, LocalDateTime.now(clock).plusDays(1));
    call(
        wrong,
        post("/creators/" + channel.getId() + "/claim"),
        Map.of("evidenceUrls", List.of("https://proof.test"), "note", "proof"),
        403);
    UUID id = submit(owner, channel);
    jdbc.update(
        "update creator_invitation set invited_email=? where id=?", wrong.getEmail(), i.getId());
    approve(id, 403);
    assertThat(claims.findById(id).orElseThrow().getStatus()).isEqualTo(CreatorClaimStatus.PENDING);
  }

  @Test
  void revokeManualKeepsIndependentOauthAndDoesNotResurrectRevokedOauth() throws Exception {
    UUID id = submit(owner, channel);
    approve(id, 200);
    var at = LocalDateTime.now(clock).plusSeconds(1);
    proof("TIKTOK_OAUTH_SANDBOX", "ACTIVE", at);
    revoke(id, 200);
    assertThat(
            jdbc.queryForObject(
                "select m.user_id from creator c join creator_master m on m.id=c.master_id where"
                    + " c.id=?",
                UUID.class,
                channel.getId()))
        .isEqualTo(owner.getId());
    assertThat(
            projection
                .project(List.of(channel.getId()), false)
                .get(channel.getId())
                .verification()
                .method())
        .isEqualTo("TIKTOK_OAUTH_SANDBOX");
    proof("TIKTOK_OAUTH_SANDBOX", "REVOKED", at.plusSeconds(1));
    assertThat(
            projection
                .project(List.of(channel.getId()), false)
                .get(channel.getId())
                .verification()
                .verified())
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select verification_status from creator_identity_catalog_state where creator_id=?",
                String.class,
                channel.getId()))
        .isEqualTo("UNVERIFIED");
  }

  @Test
  void mockOrWrongOwnerProofCannotPreserveManualOwnership() throws Exception {
    UUID id = submit(owner, channel);
    approve(id, 200);
    proof("OAUTH_SIMULATED_MOCK", "ACTIVE", LocalDateTime.now(clock).plusSeconds(1));
    revoke(id, 200);
    var c = creators.findById(channel.getId()).orElseThrow();
    assertThat(c.getMaster()).isNull();
    assertThat(c.getSourceType()).isEqualTo(CreatorSourceType.PUBLIC);
    assertThat(claims.findById(id).orElseThrow().getStatus()).isEqualTo(CreatorClaimStatus.REVOKED);
  }

  @Test
  void masterPlatformAndVerificationMustMatchSameChannel() throws Exception {
    UUID id = submit(owner, channel);
    approve(id, 200);
    var m = creators.findById(channel.getId()).orElseThrow().getMaster();
    var fb = channel("FACEBOOK", "catalog-fb");
    fb.setMaster(m);
    fb.setSourceType(CreatorSourceType.AUTHORIZED);
    fb.setAuthorizedAt(LocalDateTime.now(clock));
    creators.saveAndFlush(fb);
    var result =
        call(
            dm,
            get("/data/creator-masters")
                .param("q", m.getId().toString())
                .param("platform", "FACEBOOK")
                .param("verificationStatus", "VERIFIED"),
            null,
            200);
    assertThat(result.at("/data/totalElements").asInt()).isZero();
    assertThat(
            call(
                    dm,
                    get("/data/creator-masters")
                        .param("q", m.getId().toString())
                        .param("platform", "TIKTOK")
                        .param("verificationStatus", "VERIFIED"),
                    null,
                    200)
                .at("/data/totalElements")
                .asInt())
        .isEqualTo(1);
  }

  @Test
  void identityHistoryWhitelistsAndDoesNotInventHistoricalMaster() throws Exception {
    UUID id = submit(owner, channel);
    approve(id, 200);
    long before = count("audit_log");
    var r =
        call(dm, get("/data/creator-identity-events").param("claimId", id.toString()), null, 200)
            .path("data");
    assertThat(r.path("items").size()).isEqualTo(2);
    assertThat(r.toString())
        .doesNotContain(
            "Private evidence",
            "https://proof",
            owner.getEmail(),
            "oldValue",
            "newValue",
            "description");
    r.path("items")
        .forEach(
            e -> {
              if ("SUBMIT_CREATOR_CLAIM".equals(e.path("eventType").asText()))
                assertThat(e.path("masterId").isNull() || e.path("masterId").isMissingNode())
                    .isTrue();
              else assertThat(e.path("masterId").asText()).isNotBlank();
            });
    assertThat(count("audit_log")).isEqualTo(before);
    call(
        dm,
        get("/data/creator-identity-events")
            .param("from", "2026-11-10T00:00:00")
            .param("to", "2026-11-09T00:00:00"),
        null,
        400);
    call(dm, get("/data/creator-identity-events").param("eventType", "PASSWORD_RESET"), null, 400);
  }

  @Test
  void auditFailureRollsBackApprovalAndInvitationConsumption() throws Exception {
    var i = invite(owner, LocalDateTime.now(clock).plusDays(1));
    UUID id = submit(owner, channel);
    var target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
    doThrow(new IllegalStateException("test audit failure"))
        .when((AuditService) target)
        .record(eq("REVIEW_CREATOR_CLAIM"), anyString(), any(), any(), any(), anyString());
    approve(id, 500);
    assertThat(creators.findById(channel.getId()).orElseThrow().getMaster()).isNull();
    assertThat(claims.findById(id).orElseThrow().getStatus()).isEqualTo(CreatorClaimStatus.PENDING);
    assertThat(invitations.findById(i.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
  }

  @Test
  void concurrentReviewHasOneSuccessAndOneMaster() throws Exception {
    UUID id = submit(owner, channel);
    var other = account(Role.DATA_MANAGER);
    var start = new CountDownLatch(1);
    var ready = new CountDownLatch(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var tasks = new ArrayList<Future<Integer>>();
      for (var actor : List.of(dm, other))
        tasks.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                  return mvc.perform(
                          patch("/data/creator-claims/" + id + "/review")
                              .header("Authorization", "Bearer " + jwt.generateToken(actor))
                              .contentType("application/json")
                              .content("{\"decision\":\"APPROVED\",\"reason\":\"Checked\"}"))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_master where user_id=?", Long.class, owner.getId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where entity_id=? and"
                    + " action='REVIEW_CREATOR_CLAIM'",
                Long.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void migrationV31ToV32PreservesRowsAndDoesNotInventInvitationReferences() throws Exception {
    UUID claimId = submit(owner, channel);
    String schema = "catalog_" + UUID.randomUUID().toString().replace("-", "");
    var config =
        org.flywaydb.core.Flyway.configure()
            .dataSource(jdbc.getDataSource())
            .schemas(schema)
            .defaultSchema(schema);
    config.target("31").load().migrate();
    try {
      jdbc.update(
          "insert into " + schema + ".\"user\" select * from public.\"user\" where id in (?,?)",
          dm.getId(),
          owner.getId());
      jdbc.update(
          "insert into " + schema + ".creator select * from public.creator where id=?",
          channel.getId());
      jdbc.update(
          "insert into "
              + schema
              + ".creator_claim(id,creator_id,claimant_id,evidence_urls,note,status,created_at,updated_at)"
              + " select id,creator_id,claimant_id,evidence_urls,note,status,created_at,updated_at"
              + " from public.creator_claim where id=?",
          claimId);
      String before =
          jdbc.queryForObject(
              "select to_jsonb(c)::text from " + schema + ".creator c where id=?",
              String.class,
              channel.getId());
      assertThat(
              org.flywaydb.core.Flyway.configure()
                  .dataSource(jdbc.getDataSource())
                  .schemas(schema)
                  .defaultSchema(schema)
                  .target("32")
                  .load()
                  .migrate()
                  .migrationsExecuted)
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select to_jsonb(c)::text from " + schema + ".creator c where id=?",
                  String.class,
                  channel.getId()))
          .isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "select invitation_id from " + schema + ".creator_claim where id=?",
                  UUID.class,
                  claimId))
          .isNull();
      assertThat(
              jdbc.queryForObject("select count(*) from " + schema + ".creator_master", Long.class))
          .isZero();
    } finally {
      jdbc.execute("drop schema " + schema + " cascade");
    }
  }

  @Test
  void twoChannelsApprovedConcurrentlyReuseOneMaster() throws Exception {
    UUID first = submit(owner, channel),
        second = submit(owner, channel("FACEBOOK", UUID.randomUUID().toString()));
    var start = new CountDownLatch(1);
    var ready = new CountDownLatch(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var work = new ArrayList<Future<Integer>>();
      for (UUID id : List.of(first, second))
        work.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                  return mvc.perform(
                          patch("/data/creator-claims/" + id + "/review")
                              .header("Authorization", "Bearer " + jwt.generateToken(dm))
                              .contentType("application/json")
                              .content("{\"decision\":\"APPROVED\",\"reason\":\"Proof checked\"}"))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(work.get(0).get(20, TimeUnit.SECONDS), work.get(1).get(20, TimeUnit.SECONDS)))
          .containsOnly(200);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_master where user_id=?", Long.class, owner.getId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator c join creator_master m on m.id=c.master_id where"
                    + " m.user_id=?",
                Long.class,
                owner.getId()))
        .isEqualTo(2);
  }

  @Test
  void revokeAuditFailureRollsBackOwnershipAndDecision() throws Exception {
    UUID id = submit(owner, channel);
    approve(id, 200);
    var target =
        (AuditService) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
    doThrow(new IllegalStateException("test failure"))
        .when(target)
        .record(eq("REVOKE_CREATOR_CLAIM"), anyString(), any(), any(), any(), anyString());
    revoke(id, 500);
    assertThat(claims.findById(id).orElseThrow().getStatus())
        .isEqualTo(CreatorClaimStatus.APPROVED);
    assertThat(creators.findById(channel.getId()).orElseThrow().getMaster()).isNotNull();
    assertThat(
            projection
                .project(List.of(channel.getId()), false)
                .get(channel.getId())
                .verification()
                .verified())
        .isTrue();
  }

  @Test
  void permissionOrAuthVersionChangedWhileWaitingCannotApprove() throws Exception {
    UUID id = submit(owner, channel);
    var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
    tx.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    doAnswer(
            inv -> {
              tx.execute(
                  s -> {
                    jdbc.update(
                        "update \"user\" set auth_version=auth_version+1 where id=?", dm.getId());
                    return null;
                  });
              return inv.callRealMethod();
            })
        .when(guard)
        .users(any(), any());
    approve(id, 403);
    assertThat(claims.findById(id).orElseThrow().getStatus()).isEqualTo(CreatorClaimStatus.PENDING);
    assertThat(creators.findById(channel.getId()).orElseThrow().getMaster()).isNull();
  }

  @Test
  void approvalAndRevokeSynchronizeCurrentReferencesWithoutCreatingCollaboration()
      throws Exception {
    var brand = account(Role.BRAND);
    var campaign = createCampaign(brand, createBrand(brand));
    call(
        brand,
        post("/campaigns/" + campaign + "/shortlist"),
        Map.of("creatorId", channel.getId()),
        200);
    call(
        brand,
        post("/campaigns/" + campaign + "/relationships"),
        Map.of("creatorId", channel.getId()),
        200);
    long collaborations = count("collaboration");
    UUID id = submit(owner, channel);
    approve(id, 200);
    var master =
        jdbc.queryForObject(
            "select master_id from creator where id=?", UUID.class, channel.getId());
    assertThat(
            jdbc.queryForObject(
                "select master_id from shortlist_item where campaign_id=? and creator_id=?",
                UUID.class,
                campaign,
                channel.getId()))
        .isEqualTo(master);
    assertThat(
            jdbc.queryForObject(
                "select master_id from relationship where campaign_id=? and creator_id=?",
                UUID.class,
                campaign,
                channel.getId()))
        .isEqualTo(master);
    revoke(id, 200);
    assertThat(
            jdbc.queryForObject(
                "select master_id from shortlist_item where campaign_id=? and creator_id=?",
                UUID.class,
                campaign,
                channel.getId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select master_id from relationship where campaign_id=? and creator_id=?",
                UUID.class,
                campaign,
                channel.getId()))
        .isNull();
    assertThat(count("collaboration")).isEqualTo(collaborations);
  }

  @Test
  void eventMasterSnapshotDoesNotFollowCurrentChannelOwner() throws Exception {
    UUID id = submit(owner, channel);
    approve(id, 200);
    var original =
        jdbc.queryForObject(
            "select master_id from creator where id=?", UUID.class, channel.getId());
    var next =
        masters.saveAndFlush(
            CreatorMaster.builder()
                .user(account(Role.CREATOR))
                .canonicalName("Next owner")
                .build());
    jdbc.update("update creator set master_id=? where id=?", next.getId(), channel.getId());
    var events =
        call(
                dm,
                get("/data/creator-identity-events")
                    .param("masterId", original.toString())
                    .param("creatorId", channel.getId().toString()),
                null,
                200)
            .path("data");
    assertThat(events.path("totalElements").asInt()).isEqualTo(1);
    assertThat(events.path("items").get(0).path("masterId").asText())
        .isEqualTo(original.toString());
  }

  @Test
  void transferEndpointRemainsAbsent() throws Exception {
    call(
        dm,
        post("/admin/creator-conflicts/" + UUID.randomUUID() + "/resolve"),
        Map.of("decision", "APPROVE_TRANSFER", "notes", "Not allowed"),
        404);
  }
}
