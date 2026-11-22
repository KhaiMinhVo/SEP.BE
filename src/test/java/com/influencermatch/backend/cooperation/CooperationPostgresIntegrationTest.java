package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.email.ClaimEmailCipher;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.support.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.*;

@org.springframework.context.annotation.Import(CooperationPostgresIntegrationTest.EventConfig.class)
@org.springframework.test.context.TestPropertySource(properties = "application.auth.email-verification.secret=test-email-otp-secret-at-least-32-characters")
class CooperationPostgresIntegrationTest extends PostgresApiTestSupport {
  @org.springframework.boot.test.context.TestConfiguration
  static class EventConfig {
    @org.springframework.context.annotation.Bean
    CommitProbe cooperationProbe() {
      return new CommitProbe();
    }
  }

  static class CommitProbe {
    final java.util.concurrent.ConcurrentLinkedQueue<UUID> committed =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    @org.springframework.transaction.event.TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void committed(CooperationDelivery.Queued event) {
      committed.add(event.id());
    }
  }

  @Autowired CommitProbe probe;

  @Autowired CreatorRepository creators;
  @Autowired CreatorMasterRepository masters;
  @Autowired CooperationDelivery delivery;
  @Autowired CooperationWorker worker;
  @Autowired ClaimEmailCipher invitationCipher;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;
  @MockBean VerificationEmailSender sender;
  @Autowired AuditService audit;
  User brand, creator;
  UUID campaign;
  CreatorMaster master;

  @BeforeEach
  void setup() throws Exception {
    brand = account(Role.BRAND);
    creator = account(Role.CREATOR);
    campaign = createCampaign(brand, createBrand(brand));
    jdbc.update(
        "update campaign set"
            + " status='READY_FOR_DISCOVERY',creator_mode='MULTI',target_creator_count=2,total_budget=1000000"
            + " where id=?",
        campaign);
    master =
        masters.saveAndFlush(
            CreatorMaster.builder().user(creator).canonicalName("Creator").build());
  }

  Creator channel(boolean owned) {
    var c =
        Creator.builder()
            .platform("TIKTOK")
            .externalId(UUID.randomUUID().toString())
            .userName("user" + UUID.randomUUID().toString().replace("-", ""))
            .displayName("Channel");
    if (owned)
      c.master(master)
          .sourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED)
          .authorizedAt(LocalDateTime.now());
    var channel = creators.saveAndFlush(c.build());
    if (owned)
      jdbc.update(
          "insert into creator_account_verification(id,creator_id,verified_by_user_id,verification_method,status,verified_at,created_at,updated_at)"
              + " values(gen_random_uuid(),?,?,'TIKTOK_OAUTH_SANDBOX','ACTIVE',now(),now(),now())",
          channel.getId(), creator.getId());
    return channel;
  }

  void makeContactable(List<Creator> candidates) throws Exception {
    // Outreach requires persisted recommendation membership.
    for (Creator candidate : candidates) {
      jdbc.update(
          "insert into shortlist_item(id,campaign_id,creator_id,user_id,priority,added_at,status,source)"
              + " values(gen_random_uuid(),?,?,?,'MEDIUM',now(),'SAVED','MANUAL_DISCOVERY')"
              + " on conflict(campaign_id,creator_id) do update set status='SAVED'",
          campaign, candidate.getId(), brand.getId());
    }
    var eligible = jdbc.queryForList("select creator_id from shortlist_item where campaign_id=? and status<>'REMOVED'", UUID.class,campaign);
    String snapshot = json.writeValueAsString(Map.of("candidates",eligible.stream().map(id -> Map.of("creatorId",id,"recommendationTier","HISTORY_SUPPORTED")).toList()));
    jdbc.update("insert into recommendation_run(id,campaign_id,brand_profile_id,context_version,status,candidates_count,created_at,scoring_snapshot) values(gen_random_uuid(),?,(select brand_profile_id from campaign where id=?),1,'COMPLETED',?,now(),?::jsonb)",campaign,campaign,eligible.size(),snapshot);
  }

  UUID relationship(Creator c) throws Exception {
    makeContactable(List.of(c));
    return UUID.fromString(
        call(
                brand,
                post("/campaigns/" + campaign + "/relationships"),
                Map.of("creatorId", c.getId()),
                200)
            .path("data")
            .path("id")
            .asText());
  }

  String root() {
    return "/campaigns/" + campaign + "/deals";
  }

  JsonNode create(List<UUID> rels) throws Exception {
    return call(
            brand,
            post(root()).header("Idempotency-Key", UUID.randomUUID().toString()),
            Map.of("relationshipIds", rels),
            200)
        .path("data");
  }

  Map<String, Object> proposal(long revision, List<Creator> channels, int fee) {
    return Map.of(
        "expectedRevision",
        revision,
        "fee",
        fee,
        "currency",
        "VND",
        "terms",
        "Agreed review deliverables",
        "deliverables",
        channels.stream()
            .map(
                c ->
                    Map.of(
                        "creatorId",
                        c.getId(),
                        "contentType",
                        "Review",
                        "quantity",
                        1,
                        "dueAt",
                        "2027-01-01T00:00:00Z",
                        "acceptanceChecklist",
                        List.of("Brief satisfied"),
                        "maxRevisionRounds",
                        2))
            .toList());
  }

  UUID prepared(List<Creator> channels, int fee) throws Exception {
    var rels = new ArrayList<UUID>();
    for (var c : channels) rels.add(relationship(c));
    var d = create(rels);
    UUID id = UUID.fromString(d.path("id").asText());
    call(
        brand,
        post(root() + "/" + id + "/proposals")
            .header("Idempotency-Key", UUID.randomUUID().toString()),
        proposal(1, channels, fee),
        200);
    return id;
  }

  Map<String, Object> external(long revision) {
    return Map.of(
        "expectedRevision",
        revision,
        "agreementSource",
        "EXTERNAL",
        "agreedAt",
        Instant.now().minusSeconds(30).toString(),
        "evidenceSource",
        "EMAIL",
        "evidenceUrl",
        "https://evidence.example.test/approval",
        "evidenceDescription",
        "Approval by recipient recorded by Brand");
  }

  JsonNode confirm(UUID deal, int code) throws Exception {
    return call(
            brand,
            post(root() + "/" + deal + "/confirm")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            external(2),
            code)
        .path("data");
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where campaign_id=?", Long.class, campaign);
  }

  record OnboardingFixture(UUID deal, UUID invitation, String token) {}

  OnboardingFixture onboardingInvitation(Creator channel) throws Exception {
    UUID id = prepared(List.of(channel), 100000);
    var sent = call(brand, post(root() + "/" + id + "/invitations")
        .header("Idempotency-Key", UUID.randomUUID().toString()), Map.of("expectedRevision", 2), 200).path("data");
    UUID invitation = UUID.fromString(sent.path("invitationId").asText());
    String payload = jdbc.queryForObject("select payload from cooperation_email_job where invitation_id=?", String.class, invitation);
    String token = java.net.URI.create(invitationCipher.decrypt(payload).link()).getQuery().substring("token=".length());
    return new OnboardingFixture(id, invitation, token);
  }

  @Test
  void invitedNewCreatorRegistersVerifiesEmailAndResumesOnlyAfterChannelVerification() throws Exception {
    var channel = channel(false);
    var fixture = onboardingInvitation(channel);
    String email = UUID.randomUUID() + "@creator.test";
    call(null, post("/cooperation-invitations/registration"), Map.of("token", fixture.token(), "email", email), 202);
    var link = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(sender).sendCreatorInvitation(eq(email), link.capture());
    var params = org.springframework.web.util.UriComponentsBuilder.fromUriString(link.getValue()).build().getQueryParams();
    String registrationToken = params.getFirst("token");
    assertThat(params.getFirst("cooperationToken")).isEqualTo(fixture.token());
    call(null, post("/auth/creator/register"), Map.of("invitationToken", registrationToken, "fullName", "Invited Creator",
        "password", "Password123!", "policyAcceptance", TestAuthHttp.acceptance()), 201);
    var newCreator = users.findByEmail(email).orElseThrow();
    assertThat(newCreator.getRole()).isEqualTo(Role.CREATOR);
    assertThat(jdbc.queryForObject("select master_id from creator where id=?", UUID.class, channel.getId())).isNull();
    var otp = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(sender).send(eq(email), otp.capture());
    call(null, post("/auth/verify-email"), Map.of("email", email, "otp", otp.getValue()), 200);
    newCreator = users.findByEmail(email).orElseThrow();
    login(newCreator);
    assertThat(call(newCreator, post("/cooperation-invitations/context"), Map.of("token", fixture.token()), 200)
        .path("data").path("status").asText()).isEqualTo("NEEDS_CHANNEL_VERIFICATION");
    call(newCreator, post("/cooperation-invitations/continue"), Map.of("token", fixture.token()), 403);
    UUID newMaster = jdbc.queryForObject("select id from creator_master where user_id=?", UUID.class, newCreator.getId());
    // Verification boundary has its own OAuth/Claim tests; only persisted verified ownership unlocks continuation.
    jdbc.update("update creator set master_id=?,source_type='AUTHORIZED',authorized_at=now() where id=?", newMaster, channel.getId());
    call(newCreator, post("/cooperation-invitations/continue"), Map.of("token", fixture.token()), 403);
    jdbc.update("insert into creator_account_verification(id,creator_id,verified_by_user_id,verification_method,status,verified_at,created_at,updated_at)"
        + " values(gen_random_uuid(),?,?,'TIKTOK_OAUTH_SANDBOX','ACTIVE',now(),now(),now())", channel.getId(), newCreator.getId());
    call(null, get("/cooperation-invitations/preview").param("token", fixture.token()), null, 200);
    for (int attempt=0; attempt<2; attempt++) {
      assertThat(call(newCreator, post("/cooperation-invitations/continue"), Map.of("token", fixture.token()), 200)
          .path("data").path("dealId").asText()).isEqualTo(fixture.deal().toString());
    }
    var deal = jdbc.queryForMap("select master_id,revision,accepted_revision,collaboration_id from cooperation_deal where id=?", fixture.deal());
    assertThat(deal.get("master_id")).isEqualTo(newMaster);
    assertThat(((Number) deal.get("revision")).intValue()).isEqualTo(3);
    assertThat(deal.get("accepted_revision")).isNull();
    assertThat(deal.get("collaboration_id")).isNull();
    var detail = call(newCreator, get("/creators/portal/cooperation-deals/" + fixture.deal()), null, 200).path("data");
    call(newCreator, post("/creators/portal/cooperation-deals/" + fixture.deal() + "/accept"), Map.of("expectedRevision", 3), 200);
    assertThat(jdbc.queryForObject("select accepted_revision from cooperation_deal where id=?", Integer.class, fixture.deal()))
        .isEqualTo(detail.path("proposal").path("revision").asInt());
    call(creator, post("/cooperation-invitations/continue"), Map.of("token", fixture.token()), 403);
  }

  @Test
  void registrationUsesDeliveredRecipientSnapshotAndRateLimitsRequests() throws Exception {
    var fixture = onboardingInvitation(channel(false));
    String email = UUID.randomUUID() + "@creator.test";
    jdbc.update("update cooperation_email_job set delivery_method='EMAIL_ONLY',payload=? where invitation_id=?",
        invitationCipher.encrypt(email, "https://example.test/cooperation-invitations?token=" + fixture.token()), fixture.invitation());
    call(null, post("/cooperation-invitations/registration"), Map.of("token", fixture.token(), "email", "someoneelse@creator.test"), 202);
    verify(sender, never()).sendCreatorInvitation(anyString(), anyString());
    assertThat(call(creator, post("/cooperation-invitations/context"), Map.of("token", fixture.token()), 200)
        .path("data").path("status").asText()).isEqualTo("ACCOUNT_MISMATCH");
    call(null, post("/cooperation-invitations/registration"), Map.of("token", fixture.token(), "email", email), 202);
    call(null, post("/cooperation-invitations/registration"), Map.of("token", fixture.token(), "email", email), 429);
    verify(sender, times(1)).sendCreatorInvitation(eq(email), anyString());
    call(null, post("/cooperation-invitations/continue"), Map.of("token", fixture.token()), 401);
    call(brand, post("/cooperation-invitations/continue"), Map.of("token", fixture.token()), 403);
  }

  @Test
  void expiredInvitationCannotRegisterOrResume() throws Exception {
    var fixture = onboardingInvitation(channel(false));
    jdbc.update("update cooperation_invitation set expires_at=now()-interval '1 second' where id=?", fixture.invitation());
    call(null, post("/cooperation-invitations/registration"), Map.of("token", fixture.token(), "email", "nobody@creator.test"), 409);
    call(creator, post("/cooperation-invitations/context"), Map.of("token", fixture.token()), 409);
    call(creator, post("/cooperation-invitations/continue"), Map.of("token", fixture.token()), 409);
    verifyNoInteractions(sender);
  }

  @Test
  void verifiedCreatorCanTakeSamePlannedAgreementOnce() throws Exception {
    var channel = channel(false);
    var deal = prepared(List.of(channel), 25000);
    var agreement = confirm(deal, 200);
    String collaboration = agreement.path("collaborationId").asText();
    String detail = "/collaborations/" + collaboration + "/deliverables";
    String pending = "/creators/portal/collaborations/pending-assignments";
    String accept = "/creators/portal/collaborations/" + collaboration + "/accept-assignment";
    String start = root() + "/" + deal + "/start";
    jdbc.update("update campaign set status='ACTIVE' where id=?", campaign);
    assertThat(call(brand, get(detail), null, 200).path("data").path("assignmentStatus").asText())
        .isEqualTo("WAITING_FOR_VERIFICATION");
    call(brand, post(start), Map.of("expectedRevision", 3), 409);
    jdbc.update("update creator set master_id=?,source_type='AUTHORIZED',authorized_at=now() where id=?",
        master.getId(), channel.getId());
    assertThat(call(creator, get(pending), null, 200).path("data").isEmpty()).isTrue();
    jdbc.update(
        "insert into creator_account_verification(id,creator_id,verified_by_user_id,verification_method,status,verified_at,created_at,updated_at)"
            + " values(gen_random_uuid(),?,?,'TIKTOK_OAUTH_SANDBOX','ACTIVE',now(),now(),now())",
        channel.getId(), creator.getId());
    var preview = call(creator, get(pending), null, 200).path("data").get(0);
    assertThat(preview.path("collaborationId").asText()).isEqualTo(collaboration);
    assertThat(preview.path("terms").asText()).isEqualTo("Agreed review deliverables");
    assertThat(preview.toString()).doesNotContain("\"evidenceUrl\"", "\"review\"", "\"actualCost\"", "\"masterId\"");
    call(creator, get(detail), null, 403);
    assertThat(call(brand, get(detail), null, 200).path("data").path("assignmentStatus").asText())
        .isEqualTo("WAITING_FOR_ACCEPTANCE");
    var body = Map.of("expectedRevision", 3, "proposalRevision", preview.path("proposalRevision").asInt());
    call(account(Role.CREATOR), post(accept).header("Idempotency-Key", "assignment"), body, 403);
    String token = jwt.generateToken(creator);
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    JsonNode saved;
    try {
      var attempts = new ArrayList<Future<JsonNode>>();
      for (int i = 0; i < 2; i++) {
        attempts.add(pool.submit(() -> {
          gate.await();
          return callToken(token, post(accept).header("Idempotency-Key", "assignment"), body, 200)
              .path("data");
        }));
      }
      gate.countDown();
      saved = attempts.get(0).get(20, TimeUnit.SECONDS);
      assertThat(attempts.get(1).get(20, TimeUnit.SECONDS)).isEqualTo(saved);
    } finally {
      pool.shutdownNow();
    }
    assertThat(call(creator, post(accept).header("Idempotency-Key", "assignment"), body, 200).path("data"))
        .isEqualTo(saved);
    call(creator, post(accept).header("Idempotency-Key", "new-attempt"), body, 409);
    assertThat(jdbc.queryForObject(
        "select count(*) from cooperation_history where deal_id=? and action='CREATOR_ASSIGNMENT_ACCEPTED'",
        Integer.class, deal)).isEqualTo(1);
    assertThat(call(creator, get(pending), null, 200).path("data").isEmpty()).isTrue();
    call(creator, get(detail), null, 200);
    jdbc.update("update creator_account_verification set status='REVOKED' where creator_id=?", channel.getId());
    call(creator, get(detail), null, 403);
    call(brand, post(start), Map.of("expectedRevision", 4), 409);
    jdbc.update("update creator_account_verification set status='ACTIVE' where creator_id=?", channel.getId());
    call(brand, post(start), Map.of("expectedRevision", 4), 200);
    assertThat(call(creator, get(detail), null, 200).path("data").path("status").asText())
        .isEqualTo("IN_PROGRESS");
  }

  @Test
  void multiChannelDealCreatesOnePlannedCollaborationAndBudgetCommitment() throws Exception {
    var a = channel(true);
    var b = channel(true);
    UUID id = prepared(List.of(a, b), 500000);
    var r = confirm(id, 200);
    assertThat(r.path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(r.path("collaborationStatus").asText()).isEqualTo("PLANNED");
    assertThat(count("collaboration")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_channel where collaboration_id=?",
                Long.class,
                UUID.fromString(r.path("collaborationId").asText())))
        .isEqualTo(2);
    var budget =
        call(brand, get("/campaigns/" + campaign + "/cooperation-budget"), null, 200).path("data");
    assertThat(budget.path("committedBudget").decimalValue()).isEqualByComparingTo("500000");
    assertThat(budget.path("confirmedCreatorCount").asInt()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from relationship where campaign_id=? and stage='CONFIRMED'",
                Long.class,
                campaign))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration_deliverable where collaboration_id in (select"
                    + " id from collaboration where campaign_id=?)",
                Integer.class,
                campaign))
        .isEqualTo(2);
  }

  @Test
  void publicChannelCanBeConfirmedWithoutCreatingMasterOrOwnership() throws Exception {
    var c = channel(false);
    UUID id = prepared(List.of(c), 100000);
    confirm(id, 200);
    var saved = creators.findById(c.getId()).orElseThrow();
    assertThat(saved.getMaster()).isNull();
    assertThat(saved.getSourceType())
        .isEqualTo(com.influencermatch.backend.creator.enums.CreatorSourceType.PUBLIC);
    assertThat(count("collaboration")).isOne();
  }

  @Test
  void channelsFromDifferentMastersCannotBeGrouped() throws Exception {
    var a = channel(true);
    var b = channel(false);
    call(
        brand,
        post(root()).header("Idempotency-Key", "different"),
        Map.of("relationshipIds", List.of(relationship(a), relationship(b))),
        409);
    assertThat(count("cooperation_deal")).isZero();
  }

  @Test
  void createAndConfirmIdempotencyDoNotDuplicateOrOverwrite() throws Exception {
    var c = channel(false);
    UUID rel = relationship(c);
    var body = Map.of("relationshipIds", List.of(rel));
    var req = post(root()).header("Idempotency-Key", "create-key");
    var first = call(brand, req, body, 200).path("data");
    var again =
        call(brand, post(root()).header("Idempotency-Key", "create-key"), body, 200).path("data");
    assertThat(again.path("id")).isEqualTo(first.path("id"));
    call(
        brand,
        post(root()).header("Idempotency-Key", "create-key"),
        Map.of("relationshipIds", List.of(UUID.randomUUID())),
        409);
    UUID id = UUID.fromString(first.path("id").asText());
    call(
        brand,
        post(root() + "/" + id + "/proposals").header("Idempotency-Key", "offer"),
        proposal(1, List.of(c), 100000),
        200);
    var proof = external(2);
    var confirmed =
        call(
                brand,
                post(root() + "/" + id + "/confirm").header("Idempotency-Key", "confirm"),
                proof,
                200)
            .path("data");
    var repeated =
        call(
                brand,
                post(root() + "/" + id + "/confirm").header("Idempotency-Key", "confirm"),
                proof,
                200)
            .path("data");
    assertThat(repeated.path("collaborationId")).isEqualTo(confirmed.path("collaborationId"));
    assertThat(count("collaboration")).isOne();
  }

  @Test
  void portalAcceptanceIsRevisionBoundAndCounterofferCannotSelfApprove() throws Exception {
    var c = channel(true);
    UUID id = prepared(List.of(c), 100000);
    call(
        creator,
        post("/creators/portal/cooperation-deals/" + id + "/accept"),
        Map.of("expectedRevision", 2),
        200);
    call(
        brand,
        post(root() + "/" + id + "/proposals").header("Idempotency-Key", "new-offer"),
        proposal(2, List.of(c), 200000),
        200);
    call(
        brand,
        post(root() + "/" + id + "/confirm").header("Idempotency-Key", "stale-accept"),
        Map.of("expectedRevision", 3, "agreementSource", "PORTAL"),
        409);
    call(
        creator,
        post("/creators/portal/cooperation-deals/" + id + "/counteroffers")
            .header("Idempotency-Key", "counter"),
        proposal(3, List.of(c), 150000),
        200);
    call(
        creator,
        post("/creators/portal/cooperation-deals/" + id + "/accept"),
        Map.of("expectedRevision", 4),
        409);
    assertThat(count("collaboration")).isZero();
  }

  @Test
  void acceptedPortalTermsCanBeConfirmed() throws Exception {
    var c = channel(true);
    UUID id = prepared(List.of(c), 100000);
    call(
        creator,
        post("/creators/portal/cooperation-deals/" + id + "/accept"),
        Map.of("expectedRevision", 2),
        200);
    call(
        brand,
        post(root() + "/" + id + "/confirm").header("Idempotency-Key", "portal-confirm"),
        Map.of("expectedRevision", 2, "agreementSource", "PORTAL"),
        200);
    assertThat(count("collaboration")).isOne();
  }

  @Test
  void acceptedProposalKpiCommitmentIsFrozenOnCollaboration() throws Exception {
    var c = channel(true);
    UUID relation = relationship(c);
    var created = create(List.of(relation));
    UUID id = UUID.fromString(created.path("id").asText());
    var offer = new LinkedHashMap<String, Object>(proposal(1, List.of(c), 100000));
    offer.put("kpiType", "VIEWS");
    offer.put("kpiTarget", 5000L);

    var proposed = call(
        brand,
        post(root() + "/" + id + "/proposals").header("Idempotency-Key", "kpi-offer"),
        offer,
        200).path("data");
    assertThat(proposed.path("proposal").path("kpiType").asText()).isEqualTo("VIEWS");
    assertThat(proposed.path("proposal").path("kpiTarget").asLong()).isEqualTo(5000L);
    call(creator, post("/creators/portal/cooperation-deals/" + id + "/accept"),
        Map.of("expectedRevision", 2), 200);
    var confirmed = call(brand, post(root() + "/" + id + "/confirm")
        .header("Idempotency-Key", "kpi-confirm"),
        Map.of("expectedRevision", 2, "agreementSource", "PORTAL"), 200).path("data");
    UUID collaboration = UUID.fromString(confirmed.path("collaborationId").asText());

    assertThat(jdbc.queryForObject(
        "select committed_kpi_type from collaboration where id=?", String.class, collaboration))
        .isEqualTo("VIEWS");
    assertThat(jdbc.queryForObject(
        "select committed_kpi_target from collaboration where id=?", Long.class, collaboration))
        .isEqualTo(5000L);
  }

  @Test
  void ownershipTransferRevokesPrivateDealAndCollaborationAccess() throws Exception {
    var c = channel(true);
    UUID id = prepared(List.of(c), 100000);
    var r = confirm(id, 200);
    User other = account(Role.CREATOR);
    var next =
        masters.saveAndFlush(CreatorMaster.builder().user(other).canonicalName("Other").build());
    jdbc.update("update creator set master_id=? where id=?", next.getId(), c.getId());
    call(creator, get("/creators/portal/cooperation-deals/" + id), null, 404);
    call(other, get("/creators/portal/cooperation-deals/" + id), null, 404);
    call(creator, get("/collaborations/" + r.path("collaborationId").asText()), null, 403);
  }

  @Test
  void auditFailureRollsBackCollaborationAgreementAndCommitment() throws Exception {
    var c = channel(false);
    UUID id = prepared(List.of(c), 100000);
    jdbc.execute(
        "create function reject_deal_audit() returns trigger language plpgsql as $$ begin if"
            + " NEW.action='DEAL_CONFIRMED' and NEW.entity_id='"
            + id
            + "'::uuid then raise exception 'audit unavailable'; end if; return NEW; end $$");
    jdbc.execute(
        "create trigger reject_deal_audit before insert on audit_log for each row execute function"
            + " reject_deal_audit()");
    try {
      confirm(id, 500);
    } finally {
      jdbc.execute("drop trigger reject_deal_audit on audit_log");
      jdbc.execute("drop function reject_deal_audit()");
    }
    assertThat(count("collaboration")).isZero();
    assertThat(
            jdbc.queryForObject("select status from cooperation_deal where id=?", String.class, id))
        .isEqualTo("NEGOTIATING");
  }

  @Test
  void concurrentConfirmationCannotExceedCampaignBudget() throws Exception {
    var a = channel(false);
    var b = channel(false);
    UUID x = prepared(List.of(a), 700000), y = prepared(List.of(b), 700000);
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var futures = new ArrayList<Future<Integer>>();
      for (UUID id : List.of(x, y))
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  var req =
                      post(root() + "/" + id + "/confirm")
                          .header("Authorization", "Bearer " + jwt.generateToken(brand))
                          .header("Idempotency-Key", UUID.randomUUID().toString())
                          .contentType("application/json")
                          .content(json.writeValueAsBytes(external(2)));
                  return mvc.perform(req).andReturn().getResponse().getStatus();
                }));
      gate.countDown();
      assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(count("collaboration")).isOne();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void singleCreatorLimitBlocksDifferentRecipientAndMultiOnlyWarns() throws Exception {
    jdbc.update(
        "update campaign set creator_mode='SINGLE',target_creator_count=1 where id=?", campaign);
    UUID a = prepared(List.of(channel(false)), 100000),
        b = prepared(List.of(channel(false)), 100000);
    confirm(a, 200);
    confirm(b, 409);
    jdbc.update(
        "update campaign set creator_mode='MULTI',target_creator_count=1 where id=?", campaign);
    assertThat(confirm(b, 200).path("warnings").toString())
        .contains("TARGET_CREATOR_COUNT_EXCEEDED");
  }

  @Test
  void plannedCancellationReleasesBudgetAndStartRequiresActiveCampaign() throws Exception {
    UUID id = prepared(List.of(channel(false)), 100000);
    confirm(id, 200);
    call(brand, post(root() + "/" + id + "/start"), Map.of("expectedRevision", 3), 409);
    call(
        brand,
        post(root() + "/" + id + "/cancel"),
        Map.of("expectedRevision", 3, "reason", "Agreement cancelled before start"),
        200);
    assertThat(
            call(brand, get("/campaigns/" + campaign + "/cooperation-budget"), null, 200)
                .path("data")
                .path("committedBudget")
                .decimalValue())
        .isEqualByComparingTo("0");
    UUID second = prepared(List.of(channel(true)), 100000);
    confirm(second, 200);
    jdbc.update("update campaign set status='ACTIVE' where id=?", campaign);
    call(brand, post(root() + "/" + second + "/start"), Map.of("expectedRevision", 3), 200);
    call(
        brand,
        post(root() + "/" + second + "/cancel"),
        Map.of("expectedRevision", 4, "reason", "Too late"),
        409);
  }

  @Test
  void manualLinkPreviewContainsNoFeeIdentityOrPrivateTerms() throws Exception {
    UUID id = prepared(List.of(channel(false)), 100000);
    var sent =
        call(
                brand,
                post(root() + "/" + id + "/invitations").header("Idempotency-Key", "manual"),
                Map.of("expectedRevision", 2),
                200)
            .path("data");
    assertThat(sent.path("emailStatus").asText()).isEqualTo("MANUAL_REQUIRED");
    String invitation = sent.path("invitationId").asText();
    String link =
        call(brand, get("/cooperation-invitations/" + invitation + "/manual-link"), null, 200)
            .path("data")
            .path("link")
            .asText();
    String token = java.net.URI.create(link).getRawQuery().substring("token=".length());
    String legacyLink = "https://old-frontend.example/cooperation-invitations?token=" + token;
    jdbc.update(
        "update cooperation_email_job set payload=? where invitation_id=?",
        invitationCipher.encrypt("", legacyLink), UUID.fromString(invitation));
    var updated =
        call(brand, get("/cooperation-invitations/" + invitation + "/manual-link"), null, 200)
            .path("data");
    assertThat(updated.path("link").asText()).isEqualTo(link);
    var preview =
        call(null, get("/cooperation-invitations/preview").param("token", token), null, 200);
    assertThat(preview.toString()).doesNotContain("100000", "Agreed review", "email", "masterId");
    assertThat(
            jdbc.queryForObject(
                "select payload from cooperation_email_job where invitation_id=?",
                String.class,
                UUID.fromString(invitation)))
        .doesNotContain(token);
    verifyNoInteractions(sender);
  }

  @Test
  void portalNotificationIsPersistedAndReadOnlyByRecipient() throws Exception {
    UUID id = prepared(List.of(channel(true)), 100000);
    var sent =
        call(
                brand,
                post(root() + "/" + id + "/invitations").header("Idempotency-Key", "send"),
                Map.of("expectedRevision", 2),
                200)
            .path("data");
    assertThat(sent.path("emailStatus").asText()).isEqualTo("QUEUED");
    var notices = call(creator, get("/creators/portal/notifications"), null, 200).path("data");
    assertThat(notices.size()).isOne();
    UUID notice = UUID.fromString(notices.get(0).path("id").asText());
    assertThat(notices.get(0).path("is_read").asBoolean()).isFalse();
    call(
        account(Role.CREATOR),
        post("/creators/portal/notifications/" + notice + "/read"),
        null,
        404);
    call(creator, post("/creators/portal/notifications/" + notice + "/read"), null, 200);
  }

  @Test
  void emailFailureRetainsInvitationAndRetriesWithLeaseFencing() throws Exception {
    UUID id = prepared(List.of(channel(true)), 100000);
    var sent =
        call(
                brand,
                post(root() + "/" + id + "/invitations").header("Idempotency-Key", "send"),
                Map.of("expectedRevision", 2),
                200)
            .path("data");
    UUID inv = UUID.fromString(sent.path("invitationId").asText());
    UUID job =
        jdbc.queryForObject(
            "select id from cooperation_email_job where invitation_id=?", UUID.class, inv);
    var work = delivery.prepare(job);
    assertThat(work).isNotNull();
    assertThat(delivery.prepare(job)).isNull();
    jdbc.update(
        "update cooperation_email_job set lease_until=now()-interval '1 second' where id=?", job);
    var newer = delivery.prepare(job);
    delivery.finish(work, true);
    assertThat(
            jdbc.queryForObject(
                "select status from cooperation_email_job where id=?", String.class, job))
        .isEqualTo("PROCESSING");
    delivery.finish(newer, false);
    assertThat(
            jdbc.queryForObject(
                "select status from cooperation_invitation where id=?", String.class, inv))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select attempts from cooperation_email_job where id=?", Integer.class, job))
        .isEqualTo(2);
    assertThat(count("cooperation_deal")).isOne();
  }

  @Test
  void missingSmtpAndEnqueueFailureRollBackInvitationAndNotification() throws Exception {
    UUID id = prepared(List.of(channel(true)), 100000);
    doThrow(
            new com.influencermatch.backend.exception.BusinessException(
                com.influencermatch.backend.exception.ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,
                "SMTP unavailable"))
        .when(sender)
        .requireConfigured();
    call(
        brand,
        post(root() + "/" + id + "/invitations").header("Idempotency-Key", "unavailable"),
        Map.of("expectedRevision", 2),
        503);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from cooperation_invitation where deal_id=?", Long.class, id))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from notification where entity_id=?", Long.class, id))
        .isZero();
  }

  @Test
  void legacyEndpointCannotCreateWithoutConsentAndStageCannotConfirmDirectly() throws Exception {
    var c = channel(false);
    UUID r = relationship(c);
    call(
        brand,
        post("/campaigns/" + campaign + "/collaborations"),
        Map.of("relationshipId", r, "agreedFee", 100000, "currency", "VND"),
        400);
    call(
        brand,
        patch("/campaigns/" + campaign + "/relationships/" + r + "/stage"),
        Map.of("stage", "CONFIRMED"),
        409);
    call(
        brand,
        patch("/campaigns/" + campaign + "/relationships/" + r + "/details"),
        Map.of("agreedFee", 100000),
        400);
    assertThat(count("collaboration")).isZero();
  }

  @Test
  void batchRelationshipsCommitValidItemsAndRestoreNoMetadataOnRetry() throws Exception {
    var a = channel(false);
    var b = channel(false);
    makeContactable(List.of(a, b));
    var response =
        call(
                brand,
                post("/campaigns/" + campaign + "/relationships/batch"),
                Map.of("creatorIds", List.of(a.getId(), UUID.randomUUID(), b.getId())),
                200)
            .path("data")
            .path("results");
    assertThat(response.get(0).path("action").asText()).isEqualTo("SAVED");
    assertThat(response.get(1).path("action").asText()).isEqualTo("INVALID");
    assertThat(count("relationship")).isEqualTo(2);
  }

  @Test
  void invalidFeesDeliverablesAndEvidenceNeverCreateCollaboration() throws Exception {
    var c = channel(false);
    UUID r = relationship(c);
    UUID id = UUID.fromString(create(List.of(r)).path("id").asText());
    var bad = new LinkedHashMap<>(proposal(1, List.of(c), 100000));
    bad.put("fee", 0);
    call(
        brand,
        post(root() + "/" + id + "/proposals").header("Idempotency-Key", "bad-fee"),
        bad,
        400);
    call(
        brand,
        post(root() + "/" + id + "/proposals").header("Idempotency-Key", "good"),
        proposal(1, List.of(c), 100000),
        200);
    var evidence = new LinkedHashMap<>(external(2));
    evidence.put("evidenceUrl", "http://unsafe.example.test");
    call(
        brand,
        post(root() + "/" + id + "/confirm").header("Idempotency-Key", "bad-proof"),
        evidence,
        400);
    assertThat(count("collaboration")).isZero();
  }

  @Test
  void brandCanCancelDraftWithRevisionAndOwnershipWithoutCreatingReview() throws Exception {
    var c = channel(false);
    UUID rel = relationship(c);
    UUID id = UUID.fromString(create(List.of(rel)).path("id").asText());
    var url = root() + "/" + id + "/cancel";
    call(account(Role.BRAND), post(url), Map.of("expectedRevision", 1, "reason", "Not proceeding"), 404);
    call(brand, post(url), Map.of("expectedRevision", 99, "reason", "Not proceeding"), 409);
    call(brand, post(url), Map.of("expectedRevision", 1, "reason", " "), 400);
    var closed = call(brand, post(url), Map.of("expectedRevision", 1, "reason", "Brand decided not to contact"), 200).path("data");
    assertThat(closed.path("status").asText()).isEqualTo("CANCELLED");
    assertThat(call(brand, get(root() + "/" + id), null, 200).path("data").path("status").asText()).isEqualTo("CANCELLED");
    assertThat(count("collaboration")).isZero();
    assertThat(jdbc.queryForObject("select count(*) from cooperation_history where deal_id=? and action='DEAL_CANCEL'", Long.class, id)).isEqualTo(1);
  }

  @Test
  void withdrawalInvalidatesInvitationAndCreatorDeclineDoesNotCreateCollaboration() throws Exception {
    UUID id = prepared(List.of(channel(true)), 100000);
    var sent = call(brand, post(root() + "/" + id + "/invitations").header("Idempotency-Key", "withdraw-invite"), Map.of("expectedRevision", 2, "deliveryMethod", "PORTAL_ONLY"), 200).path("data");
    UUID invitation = UUID.fromString(sent.path("invitationId").asText());
    call(brand, post(root() + "/" + id + "/cancel"), Map.of("expectedRevision", 2, "reason", "Withdraw before agreement"), 200);
    assertThat(call(brand, get("/cooperation-invitations/" + invitation + "/email-delivery"), null, 200).path("data").path("active").asBoolean()).isFalse();
    call(creator, post("/creators/portal/cooperation-deals/" + id + "/accept"), Map.of("expectedRevision", 2), 409);
    UUID other = prepared(List.of(channel(true)), 100000);
    var declined = call(creator, post("/creators/portal/cooperation-deals/" + other + "/decline"), Map.of("expectedRevision", 2), 200).path("data");
    assertThat(declined.path("status").asText()).isEqualTo("DECLINED");
    assertThat(count("collaboration")).isZero();
  }

  @Test
  void legacyBudgetInitializationUsesRevisionAndLeavesM3SnapshotsUntouched() throws Exception {
    jdbc.update("update campaign set total_budget=null,currency=null where id=?", campaign);
    var contexts =
        jdbc.queryForList(
            "select id,context_data from campaign_context where campaign_id=? order by id",
            campaign);
    var path = "/campaigns/" + campaign + "/cooperation-budget";
    call(brand, post(path), Map.of("totalBudget", 2000000, "currency", "VND"), 428);
    call(
        brand,
        post(path)
            .header(
                "If-Match",
                com.influencermatch.backend.campaign.service.CampaignRevision.tag(
                    campaign,
                    jdbc.queryForObject(
                        "select campaign_revision from campaign where id=?",
                        Long.class,
                        campaign))),
        Map.of("totalBudget", 2000000, "currency", "VND"),
        200);
    assertThat(
            jdbc.queryForObject(
                "select total_budget from campaign where id=?", BigDecimal.class, campaign))
        .isEqualByComparingTo("2000000");
    assertThat(
            jdbc.queryForList(
                "select id,context_data from campaign_context where campaign_id=? order by id",
                campaign))
        .isEqualTo(contexts);
    call(
        brand,
        post(path)
            .header(
                "If-Match",
                com.influencermatch.backend.campaign.service.CampaignRevision.tag(
                    campaign,
                    jdbc.queryForObject(
                        "select campaign_revision from campaign where id=?",
                        Long.class,
                        campaign))),
        Map.of("totalBudget", 3000000, "currency", "VND"),
        409);
  }

  @Test
  void unknownLegacyFeeBlocksNewCommitmentsInsteadOfTreatingMissingAsZero() throws Exception {
    var c = channel(false);
    UUID r = relationship(c);
    jdbc.update(
        "insert into collaboration(id,relationship_id,campaign_id,creator_id,status,updated_at)"
            + " values(?,?,?,?,'COMPLETED',now())",
        UUID.randomUUID(),
        r,
        campaign,
        c.getId());
    UUID next = prepared(List.of(channel(false)), 100000);
    confirm(next, 409);
    var budget =
        call(brand, get("/campaigns/" + campaign + "/cooperation-budget"), null, 200).path("data");
    assertThat(budget.path("status").asText()).isEqualTo("UNAVAILABLE");
    assertThat(budget.hasNonNull("committedBudget")).isFalse();
  }

  @Test
  void twoConfirmationsOfSameDealProduceOneSuccessAndOneRevisionConflict() throws Exception {
    UUID id = prepared(List.of(channel(false)), 100000);
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var list = new ArrayList<Future<Integer>>();
      for (int n = 0; n < 2; n++)
        list.add(
            pool.submit(
                () -> {
                  gate.await();
                  return mvc.perform(
                          post(root() + "/" + id + "/confirm")
                              .header("Authorization", "Bearer " + jwt.generateToken(brand))
                              .header("Idempotency-Key", UUID.randomUUID().toString())
                              .contentType("application/json")
                              .content(json.writeValueAsBytes(external(2))))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      gate.countDown();
      assertThat(List.of(list.get(0).get(), list.get(1).get())).containsExactlyInAnyOrder(200, 409);
      assertThat(count("collaboration")).isOne();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from cooperation_history where deal_id=? and"
                      + " action='DEAL_CONFIRMED'",
                  Long.class,
                  id))
          .isOne();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void cancelledPlannedDealMayBeRebookedWithoutDeletingHistory() throws Exception {
    var c = channel(false);
    UUID id = prepared(List.of(c), 100000);
    confirm(id, 200);
    call(
        brand,
        post(root() + "/" + id + "/cancel"),
        Map.of("expectedRevision", 3, "reason", "Revised agreement needed"),
        200);
    UUID replacement = prepared(List.of(c), 150000);
    confirm(replacement, 200);
    assertThat(count("collaboration")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration where campaign_id=? and status='CANCELLED'",
                Long.class,
                campaign))
        .isOne();
    assertThat(
            call(brand, get("/campaigns/" + campaign + "/cooperation-budget"), null, 200)
                .path("data")
                .path("committedBudget")
                .decimalValue())
        .isEqualByComparingTo("150000");
  }

  @Test
  void retriesUseExactBackoffAndNeverRegenerateInvitation() throws Exception {
    UUID id = prepared(List.of(channel(true)), 100000);
    var sent =
        call(
                brand,
                post(root() + "/" + id + "/invitations").header("Idempotency-Key", "retry"),
                Map.of("expectedRevision", 2),
                200)
            .path("data");
    UUID inv = UUID.fromString(sent.path("invitationId").asText());
    UUID job =
        jdbc.queryForObject(
            "select id from cooperation_email_job where invitation_id=?", UUID.class, inv);
    String hash =
        jdbc.queryForObject(
            "select token_hash from cooperation_invitation where id=?", String.class, inv);
    doThrow(new IllegalStateException("SMTP timeout with private information"))
        .when(sender)
        .sendCooperationInvitation(anyString(), anyString());
    for (int attempt = 1; attempt <= 5; attempt++) {
      worker.process(job);
      var row = jdbc.queryForMap("select * from cooperation_email_job where id=?", job);
      assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(attempt);
      if (attempt < 5) {
        assertThat(
                Duration.between(
                        CooperationDelivery.instant(row.get("last_attempt_at")),
                        CooperationDelivery.instant(row.get("next_attempt_at")))
                    .getSeconds())
            .isEqualTo(15L << (attempt - 1));
        jdbc.update(
            "update cooperation_email_job set next_attempt_at=now()-interval '1 second' where id=?",
            job);
      } else assertThat(row.get("status")).isEqualTo("FAILED");
    }
    assertThat(
            jdbc.queryForObject(
                "select payload from cooperation_email_job where id=?", String.class, job))
        .isNotBlank();
    call(brand, post("/cooperation-invitations/" + inv + "/retry-email"), null, 429);
    jdbc.update(
        "update cooperation_email_job set last_attempt_at=now()-interval '61 seconds' where id=?",
        job);
    call(brand, post("/cooperation-invitations/" + inv + "/retry-email"), null, 200);
    call(brand, post("/cooperation-invitations/" + inv + "/retry-email"), null, 409);
    assertThat(
            jdbc.queryForObject(
                "select token_hash from cooperation_invitation where id=?", String.class, inv))
        .isEqualTo(hash);
    verify(sender, times(5)).sendCooperationInvitation(anyString(), anyString());
  }

  @Test
  void resendUsesCurrentFrontendAndPreservesTokenAndExpiry() throws Exception {
    UUID deal = prepared(List.of(channel(true)), 100000);
    var sent =
        call(
                brand,
                post(root() + "/" + deal + "/invitations").header("Idempotency-Key", "resend"),
                Map.of("expectedRevision", 2),
                200)
            .path("data");
    UUID invitation = UUID.fromString(sent.path("invitationId").asText());
    var job = jdbc.queryForMap(
        "select id,payload from cooperation_email_job where invitation_id=?", invitation);
    UUID jobId = (UUID) job.get("id");
    var original = invitationCipher.decrypt(job.get("payload").toString());
    String legacyLink = "https://old-frontend.example/cooperation-invitations?"
        + java.net.URI.create(original.link()).getRawQuery();
    jdbc.update("update cooperation_email_job set payload=? where id=?",
        invitationCipher.encrypt(original.email(), legacyLink), jobId);

    String invitationQuery = "select token_hash,expires_at from cooperation_invitation where id=?";
    var originalIdentity = jdbc.queryForMap(invitationQuery, invitation);
    worker.process(jobId);
    jdbc.update(
        "update cooperation_email_job set last_attempt_at=now()-interval '61 seconds' where id=?",
        jobId);
    call(brand, post("/cooperation-invitations/" + invitation + "/resend-email"), null, 200);
    worker.process(jobId);

    verify(sender, times(2)).sendCooperationInvitation(original.email(), original.link());
    assertThat(jdbc.queryForMap(invitationQuery, invitation)).isEqualTo(originalIdentity);

    jdbc.update(
        "update cooperation_invitation set expires_at=now()-interval '1 second' where id=?",
        invitation);
    assertThat(delivery.due()).contains(jobId);
    worker.process(jobId);
    var expired = jdbc.queryForMap(
        "select status,payload from cooperation_email_job where id=?", jobId);
    assertThat(expired.get("status")).isEqualTo("SENT");
    assertThat(expired.get("payload")).isNull();
    call(brand, post("/cooperation-invitations/" + invitation + "/resend-email"), null, 409);
    verify(sender, times(2)).sendCooperationInvitation(anyString(), anyString());
  }

  @Test
  void replacementAndExpiryClearPayloadAndNeverSendMail() throws Exception {
    var c = channel(true);
    UUID id = prepared(List.of(c), 100000);
    var sent =
        call(
                brand,
                post(root() + "/" + id + "/invitations").header("Idempotency-Key", "send1"),
                Map.of("expectedRevision", 2),
                200)
            .path("data");
    UUID inv = UUID.fromString(sent.path("invitationId").asText());
    UUID job =
        jdbc.queryForObject(
            "select id from cooperation_email_job where invitation_id=?", UUID.class, inv);
    call(
        brand,
        post(root() + "/" + id + "/proposals").header("Idempotency-Key", "replace"),
        proposal(2, List.of(c), 150000),
        200);
    worker.process(job);
    assertThat(
            jdbc.queryForObject(
                "select payload from cooperation_email_job where id=?", String.class, job))
        .isNull();
    verify(sender, never()).sendCooperationInvitation(anyString(), anyString());
    var newer =
        call(
                brand,
                post(root() + "/" + id + "/invitations").header("Idempotency-Key", "send2"),
                Map.of("expectedRevision", 3),
                200)
            .path("data");
    UUID next = UUID.fromString(newer.path("invitationId").asText());
    UUID nextJob =
        jdbc.queryForObject(
            "select id from cooperation_email_job where invitation_id=?", UUID.class, next);
    jdbc.update(
        "update cooperation_invitation set expires_at=now()-interval '1 second' where id=?", next);
    assertThat(delivery.due()).contains(nextJob);
    worker.process(nextJob);
    assertThat(
            jdbc.queryForObject(
                "select status from cooperation_email_job where id=?", String.class, nextJob))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "select payload from cooperation_email_job where id=?", String.class, nextJob))
        .isNull();
  }

  @Test
  void disablingCreatorPermissionBlocksReadingAndAcceptingWithSameToken() throws Exception {
    UUID id = prepared(List.of(channel(true)), 100000);
    String token = jwt.generateToken(creator);
    UUID override = UUID.randomUUID();
    jdbc.update(
        "insert into role_permission_override(id,role,permission,enabled)"
            + " values(?,'CREATOR','RESPOND_OWN_COOPERATION',false)",
        override);
    try {
      callToken(token, get("/creators/portal/cooperation-deals/" + id), null, 403);
      callToken(
          token,
          post("/creators/portal/cooperation-deals/" + id + "/accept"),
          Map.of("expectedRevision", 2),
          403);
    } finally {
      jdbc.update("delete from role_permission_override where id=?", override);
    }
  }

  @Test
  void allPrivateResourcesRejectOtherBrandAndOtherCreator() throws Exception {
    UUID id = prepared(List.of(channel(true)), 100000);
    User stranger = account(Role.BRAND);
    call(stranger, get(root() + "/" + id), null, 404);
    call(
        stranger,
        post(root() + "/" + id + "/confirm").header("Idempotency-Key", "foreign"),
        external(2),
        404);
    call(account(Role.CREATOR), get("/creators/portal/cooperation-deals/" + id), null, 404);
    assertThat(count("collaboration")).isZero();
  }

  @Test
  void proposalHistoryIsImmutableAndContainsPriorFees() throws Exception {
    var c = channel(true);
    UUID id = prepared(List.of(c), 100000);
    call(
        brand,
        post(root() + "/" + id + "/proposals").header("Idempotency-Key", "history-next"),
        proposal(2, List.of(c), 200000),
        200);
    var history = call(brand, get(root() + "/" + id + "/history"), null, 200).path("data");
    assertThat(history.path("proposals").size()).isEqualTo(2);
    assertThat(history.path("proposals").get(0).path("fee").decimalValue())
        .isEqualByComparingTo("100000");
    assertThatThrownBy(
            () -> jdbc.update("update cooperation_proposal set fee=1 where deal_id=?", id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(count("collaboration")).isZero();
  }

  @Test
  void enqueuePublishesOnlyAfterCommitAndRollbackNeverDispatches() throws Exception {
    UUID id = prepared(List.of(channel(true)), 100000);
    var d = jdbc.queryForMap("select * from cooperation_deal where id=?", id);
    var p = jdbc.queryForMap("select * from cooperation_proposal where deal_id=?", id);
    UUID rolled =
        tx.execute(
            status -> {
              var result = delivery.enqueue(d, p);
              UUID job =
                  jdbc.queryForObject(
                      "select id from cooperation_email_job where invitation_id=?",
                      UUID.class,
                      result.get("invitationId"));
              assertThat(probe.committed).doesNotContain(job);
              status.setRollbackOnly();
              return job;
            });
    assertThat(probe.committed).doesNotContain(rolled);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from cooperation_email_job where id=?", Long.class, rolled))
        .isZero();
    UUID committed =
        tx.execute(
            status -> {
              var result = delivery.enqueue(d, p);
              UUID job =
                  jdbc.queryForObject(
                      "select id from cooperation_email_job where invitation_id=?",
                      UUID.class,
                      result.get("invitationId"));
              assertThat(probe.committed).doesNotContain(job);
              return job;
            });
    assertThat(probe.committed).contains(committed);
    doAnswer(
            invocation -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return null;
            })
        .when(sender)
        .sendCooperationInvitation(anyString(), anyString());
    worker.process(committed);
    assertThat(
            jdbc.queryForObject(
                "select status from cooperation_email_job where id=?", String.class, committed))
        .isEqualTo("SENT");
    assertThat(
            jdbc.queryForObject(
                "select payload from cooperation_email_job where id=?", String.class, committed))
        .isNotBlank();
    // A scheduler/event duplicate must not resend an already successful job.
    worker.process(committed);
    assertThat(
            jdbc.queryForObject(
                "select attempts from cooperation_email_job where id=?", Integer.class, committed))
        .isEqualTo(1);
  }

  @Test
  void enqueueDatabaseFailureRollsBackInvitationAndNotification() throws Exception {
    UUID id = prepared(List.of(channel(true)), 100000);
    jdbc.execute(
        "create function reject_cooperation_enqueue() returns trigger language plpgsql as $$ begin"
            + " if exists(select 1 from cooperation_invitation i where i.id=NEW.invitation_id and"
            + " i.deal_id='"
            + id
            + "'::uuid) then raise exception 'enqueue unavailable';end if;return NEW;end $$");
    jdbc.execute(
        "create trigger reject_cooperation_enqueue before insert on cooperation_email_job for each"
            + " row execute function reject_cooperation_enqueue()");
    try {
      call(
          brand,
          post(root() + "/" + id + "/invitations").header("Idempotency-Key", "db-fail"),
          Map.of("expectedRevision", 2),
          500);
    } finally {
      jdbc.execute("drop trigger reject_cooperation_enqueue on cooperation_email_job");
      jdbc.execute("drop function reject_cooperation_enqueue()");
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from cooperation_invitation where deal_id=?", Long.class, id))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from notification where entity_id=?", Long.class, id))
        .isZero();
  }

  @Test
  void concurrentWorkersAcquireOnlyOneLease() throws Exception {
    UUID deal = prepared(List.of(channel(true)), 100000);
    UUID inv =
        UUID.fromString(
            call(
                    brand,
                    post(root() + "/" + deal + "/invitations").header("Idempotency-Key", "workers"),
                    Map.of("expectedRevision", 2),
                    200)
                .path("data")
                .path("invitationId")
                .asText());
    UUID job =
        jdbc.queryForObject(
            "select id from cooperation_email_job where invitation_id=?", UUID.class, inv);
    var latch = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a =
          pool.submit(
              () -> {
                latch.await();
                return delivery.prepare(job);
              });
      var b =
          pool.submit(
              () -> {
                latch.await();
                return delivery.prepare(job);
              });
      latch.countDown();
      var work = Arrays.asList(a.get(), b.get());
      assertThat(work.stream().filter(Objects::nonNull).count()).isOne();
      assertThat(
              jdbc.queryForObject(
                  "select attempts from cooperation_email_job where id=?", Integer.class, job))
          .isOne();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void manualRetryBudgetIsFivePerHourAndConcurrentRetryQueuesOnce() throws Exception {
    UUID deal = prepared(List.of(channel(true)), 100000);
    UUID inv =
        UUID.fromString(
            call(
                    brand,
                    post(root() + "/" + deal + "/invitations")
                        .header("Idempotency-Key", "manual-limits"),
                    Map.of("expectedRevision", 2),
                    200)
                .path("data")
                .path("invitationId")
                .asText());
    UUID job =
        jdbc.queryForObject(
            "select id from cooperation_email_job where invitation_id=?", UUID.class, inv);
    for (int attempt = 0; attempt < 5; attempt++) {
      jdbc.update(
          "update cooperation_email_job set status='FAILED',last_attempt_at=now()-interval '61"
              + " seconds' where id=?",
          job);
      call(brand, post("/cooperation-invitations/" + inv + "/retry-email"), null, 200);
    }
    jdbc.update(
        "update cooperation_email_job set status='FAILED',last_attempt_at=now()-interval '61"
            + " seconds' where id=?",
        job);
    call(brand, post("/cooperation-invitations/" + inv + "/retry-email"), null, 429);
    jdbc.update("update cooperation_email_job set manual_retries='[]'::jsonb where id=?", job);
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var results = new ArrayList<Future<Integer>>();
      for (int n = 0; n < 2; n++)
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  return mvc.perform(
                          post("/cooperation-invitations/" + inv + "/retry-email")
                              .header("Authorization", "Bearer " + jwt.generateToken(brand)))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      gate.countDown();
      assertThat(List.of(results.get(0).get(), results.get(1).get()))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(
              jdbc.queryForObject(
                  "select jsonb_array_length(manual_retries) from cooperation_email_job where id=?",
                  Integer.class,
                  job))
          .isOne();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void legacyConfirmationUsesSameGuardAndHashesTheEntireRequest() throws Exception {
    var c = channel(false);
    UUID id = prepared(List.of(c), 100000);
    UUID rel =
        jdbc.queryForObject(
            "select relationship_id from cooperation_member where deal_id=?", UUID.class, id);
    var body = new LinkedHashMap<String, Object>(external(2));
    body.put("dealId", id);
    body.put("relationshipId", rel);
    body.put("agreedFee", 100000);
    body.put("currency", "VND");
    body.put("note", "Original client metadata");
    var path = "/campaigns/" + campaign + "/collaborations";
    var first =
        call(brand, post(path).header("Idempotency-Key", "legacy-idem"), body, 201).path("data");
    assertThat(first.path("status").asText()).isEqualTo("PLANNED");
    var again =
        call(brand, post(path).header("Idempotency-Key", "legacy-idem"), body, 201).path("data");
    assertThat(again.path("id")).isEqualTo(first.path("id"));
    body.put("note", "Changed client metadata");
    call(brand, post(path).header("Idempotency-Key", "legacy-idem"), body, 409);
    assertThat(count("collaboration")).isOne();
  }
}
