package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.creator.portal.CreatorPortalProfiles;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CreatorPortalProfilePostgresIntegrationTest extends ExecutionScenarioSupport {
  static final String PROFILE = "/creators/portal/profile";

  JsonNode profile(User actor) throws Exception {
    return call(actor, get(PROFILE), null, 200).path("data");
  }

  JsonNode edit(User actor, String body, Long revision, int status) throws Exception {
    var req = patch(PROFILE);
    if (revision != null)
      req.header("If-Match", CreatorPortalProfiles.tag(actor.getId(), revision));
    return call(actor, req, json.readTree(body), status).path("data");
  }

  JsonNode detail() throws Exception {
    return call(brand, get("/creators/" + channel), null, 200).path("data");
  }

  JsonNode contacts(User actor) throws Exception {
    return call(actor, get("/creators/" + channel + "/contacts"), null, 200).path("data");
  }

  void proof(UUID creator, User actor, String method) {
    jdbc.update(
        "insert into"
            + " creator_account_verification(id,creator_id,verified_by_user_id,verification_method,status,verified_at,created_at,updated_at)"
            + " values(?,?,?,?,'ACTIVE',now(),now(),now())",
        UUID.randomUUID(),
        creator,
        actor.getId(),
        method);
  }

  long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  @Test
  void getAndEmptyPatchDoNotCreateMasterProfilePortfolioOrAudit() throws Exception {
    var legacy = account(Role.CREATOR);
    long m = count("creator_master"),
        p = count("creator_portal_profile"),
        a = count("audit_log"),
        portfolios = count("creator_portfolio_item");
    var v = profile(legacy);
    assertThat(v.path("masterId").isNull()).isTrue();
    assertThat(v.path("profileExists").asBoolean()).isFalse();
    assertThat(v.path("profileRevision").asLong()).isZero();
    edit(legacy, "{}", 0L, 200);
    assertThat(count("creator_master")).isEqualTo(m);
    assertThat(count("creator_portal_profile")).isEqualTo(p);
    assertThat(count("creator_portfolio_item")).isEqualTo(portfolios);
    assertThat(count("audit_log")).isEqualTo(a);
  }

  @Test
  void patchCreatesOneSharedProfilePreservesOmittedFieldsAndDoesNotChangeCrawlerData()
      throws Exception {
    String location =
        jdbc.queryForObject("select location from creator where id=?", String.class, channel);
    long metrics = count("public_creator_metric");
    var p =
        edit(
            owner,
            "{\"introduction\":\"  Creator bio "
                + " \",\"serviceAreas\":[\"B\",\"A\"],\"portfolio\":[{\"title\":\"Campaign\",\"url\":\"https://portfolio.test/work\"}]}",
            0L,
            200);
    assertThat(p.path("profileRevision").asLong()).isEqualTo(1);
    assertThat(p.at("/profile/introduction").asText()).isEqualTo("Creator bio");
    edit(owner, "{\"acceptedNiches\":[\"Beauty\"]}", 1L, 200);
    assertThat(profile(owner).at("/profile/introduction").asText()).isEqualTo("Creator bio");
    var noop = edit(owner, "{\"serviceAreas\":[\"A\",\"B\"]}", 2L, 200);
    assertThat(noop.path("profileRevision").asLong()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject("select location from creator where id=?", String.class, channel))
        .isEqualTo(location);
    assertThat(count("public_creator_metric")).isEqualTo(metrics);
  }

  @Test
  void missingMalformedStaleAndForeignEtagsAreRejected() throws Exception {
    edit(owner, "{\"introduction\":\"Bio\"}", null, 428);
    call(owner, patch(PROFILE).header("If-Match", "bad"), Map.of("introduction", "Bio"), 400);
    edit(owner, "{\"introduction\":\"Bio\"}", 0L, 200);
    edit(owner, "{\"introduction\":\"Lost update\"}", 0L, 409);
    assertThat(profile(owner).at("/profile/introduction").asText()).isEqualTo("Bio");
  }

  @Test
  void concurrentFirstPatchHasOneSuccessAndOneConflict() throws Exception {
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    String token = jwt.generateToken(owner);
    try {
      Callable<Integer> task =
          () -> {
            start.await();
            return mvc.perform(
                    patch(PROFILE)
                        .header("Authorization", "Bearer " + token)
                        .header("If-Match", CreatorPortalProfiles.tag(owner.getId(), 0))
                        .contentType("application/json")
                        .content("{\"introduction\":\"Only one\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var a = pool.submit(task);
      var b = pool.submit(task);
      start.countDown();
      assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from creator_portal_profile p join creator_master m on"
                      + " m.id=p.master_id where m.user_id=?",
                  Long.class,
                  owner.getId()))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void auditFailureRollsBackNewMasterProfileAndPortfolio() throws Exception {
    var legacy = account(Role.CREATOR);
    doThrow(new IllegalStateException("Audit unavailable"))
        .when(
            (com.influencermatch.backend.audit.service.AuditService)
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit))
        .record(eq("UPDATE_CREATOR_PROFILE"), any(), any(), any(), any(), any());
    edit(
        legacy,
        "{\"introduction\":\"Bio\",\"portfolio\":[{\"title\":\"Work\",\"url\":\"https://portfolio.test/work\"}]}",
        0L,
        500);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_master where user_id=?", Long.class, legacy.getId()))
        .isZero();
  }

  @Test
  void publicProjectionNeedsRealEvidenceAndAppliesSharingOnNextRequest() throws Exception {
    edit(
        owner,
        "{\"introduction\":\"Visible"
            + " bio\",\"portfolio\":[{\"title\":\"Work\",\"url\":\"https://portfolio.test/work\"}]}",
        0L,
        200);
    var legacy = detail();
    assertThat(legacy.path("verification").path("status").asText()).isEqualTo("LEGACY_UNVERIFIED");
    assertThat(legacy.path("verifiedByInfluencerMatch").asBoolean()).isFalse();
    assertThat(legacy.path("declaredProfile").isNull()).isTrue();
    proof(channel, owner, "TIKTOK_OAUTH_SANDBOX");
    var verified = detail();
    assertThat(verified.path("verifiedByInfluencerMatch").asBoolean()).isTrue();
    assertThat(verified.at("/verification/environment").asText()).isEqualTo("SANDBOX");
    assertThat(verified.at("/declaredProfile/introduction").asText()).isEqualTo("Visible bio");
    assertThat(verified.at("/declaredProfile/portfolio").size()).isEqualTo(1);
    edit(owner, "{\"sharing\":{\"introduction\":false,\"portfolio\":false}}", 1L, 200);
    var hidden = detail();
    assertThat(hidden.at("/declaredProfile/introduction").isNull()).isTrue();
    assertThat(hidden.at("/declaredProfile/portfolio").isNull()).isTrue();
  }

  @Test
  void contactsArePrivateByDefaultAndNeverExposeAccountEmail() throws Exception {
    proof(channel, owner, "TIKTOK_OAUTH_SANDBOX");
    edit(
        owner,
        "{\"contacts\":{\"bookingEmail\":{\"value\":\"booking-private@example.test\"},\"zalo\":{\"value\":\"private-zalo\"}}}",
        0L,
        200);
    var result = contacts(brand);
    assertThat(result.toString())
        .doesNotContain("booking-private@example.test", "private-zalo", owner.getEmail());
    assertThat(result.at("/bookingEmail/availability").asText()).isEqualTo("NOT_SHARED");
    assertThat(detail().toString())
        .doesNotContain("booking-private@example.test", "private-zalo", owner.getEmail());
    var outsider = account(Role.BRAND);
    assertThat(contacts(outsider).toString()).doesNotContain("booking-private@example.test");
  }

  @Test
  void brandAndConfirmedPartnerVisibilitiesDoNotGrantAccessToOtherBrands() throws Exception {
    proof(channel, owner, "TIKTOK_OAUTH_SANDBOX");
    edit(
        owner,
        "{\"contacts\":{\"bookingEmail\":{\"value\":\"booking@example.test\",\"visibility\":\"BRANDS\"},\"zalo\":{\"value\":\"partner-zalo\",\"visibility\":\"CONFIRMED_PARTNERS\"}}}",
        0L,
        200);
    var other = account(Role.BRAND);
    assertThat(contacts(other).at("/bookingEmail/value").asText())
        .isEqualTo("booking@example.test");
    assertThat(contacts(other).at("/zalo/value").isNull()).isTrue();
    assertThat(contacts(brand).at("/zalo/value").asText()).isEqualTo("partner-zalo");
    jdbc.update("update cooperation_deal set status='CANCELLED' where id=?", deal);
    assertThat(contacts(brand).at("/zalo/value").isNull()).isTrue();
  }

  @Test
  void completedCollaborationStillQualifiesAsConfirmedPartner() throws Exception {
    proof(channel, owner, "TIKTOK_OAUTH_SANDBOX");
    edit(
        owner,
        "{\"contacts\":{\"managerContact\":{\"value\":\"Partner"
            + " manager\",\"visibility\":\"CONFIRMED_PARTNERS\"}}}",
        0L,
        200);
    ready();
    accept();
    request(brand, root + "/review", review(), 200);
    assertThat(contacts(brand).at("/managerContact/value").asText()).isEqualTo("Partner manager");
  }

  @Test
  void changingOwnerCannotExposeFormerProfileOrInheritPartnership() throws Exception {
    proof(channel, owner, "TIKTOK_OAUTH_SANDBOX");
    edit(owner, "{\"introduction\":\"Former owner profile\"}", 0L, 200);
    var next = account(Role.CREATOR);
    edit(
        next,
        "{\"contacts\":{\"zalo\":{\"value\":\"New private"
            + " partner\",\"visibility\":\"CONFIRMED_PARTNERS\"}}}",
        0L,
        200);
    UUID nextMaster = UUID.fromString(profile(next).path("masterId").asText());
    jdbc.update("update creator set master_id=? where id=?", nextMaster, channel);
    proof(channel, next, "TIKTOK_OAUTH_SANDBOX");
    assertThat(detail().toString()).doesNotContain("Former owner profile");
    assertThat(contacts(brand).toString()).doesNotContain("New private partner");
  }

  @Test
  void disabledPermissionsAndOtherRolesCannotReadPrivateProfileOrContacts() throws Exception {
    call(brand, get(PROFILE), null, 403);
    call(account(Role.ADMIN), get("/creators/" + channel + "/contacts"), null, 403);
    jdbc.update(
        "insert into role_permission_override(id,role,permission,enabled)"
            + " values(gen_random_uuid(),'BRAND','VIEW_CREATOR_CONTACT',false) on"
            + " conflict(role,permission) do update set enabled=false");
    try {
      call(brand, get("/creators/" + channel + "/contacts"), null, 403);
    } finally {
      jdbc.update(
          "delete from role_permission_override where role='BRAND' and"
              + " permission='VIEW_CREATOR_CONTACT'");
    }
    jdbc.update(
        "insert into role_permission_override(id,role,permission,enabled)"
            + " values(gen_random_uuid(),'CREATOR','MANAGE_OWN_CREATOR_PROFILE',false) on"
            + " conflict(role,permission) do update set enabled=false");
    try {
      edit(owner, "{\"introduction\":\"No access\"}", 0L, 403);
    } finally {
      jdbc.update(
          "delete from role_permission_override where role='CREATOR' and"
              + " permission='MANAGE_OWN_CREATOR_PROFILE'");
    }
  }

  @Test
  void auditDoesNotContainContactValuesAndConnectionOptionsContainNoSecrets() throws Exception {
    edit(
        owner,
        "{\"contacts\":{\"zalo\":{\"value\":\"TOP_PRIVATE_VALUE\",\"visibility\":\"BRANDS\"}}}",
        0L,
        200);
    assertThat(
            jdbc.queryForList(
                    "select to_jsonb(a)::text from audit_log a where"
                        + " action='UPDATE_CREATOR_PROFILE'",
                    String.class)
                .toString())
        .doesNotContain("TOP_PRIVATE_VALUE");
    var options =
        call(owner, get("/creators/portal/accounts/connection-options"), null, 200).path("data");
    assertThat(options.size()).isEqualTo(5);
    assertThat(options.toString())
        .doesNotContain("appSecret", "clientSecret", "encryptionKey", "accessToken");
    assertThat(options.get(4).path("supported").asBoolean()).isFalse();
  }

  @Test
  void samePortfolioPayloadIsNoopAndDoesNotAuditAgain() throws Exception {
    var p =
        edit(
            owner,
            "{\"portfolio\":[{\"title\":\"Work\",\"url\":\"https://portfolio.test/work\"}]}",
            0L,
            200);
    long audits = count("audit_log");
    var same =
        edit(
            owner,
            json.createObjectNode().set("portfolio", p.at("/profile/portfolio")).toString(),
            1L,
            200);
    assertThat(same.path("profileRevision").asLong()).isEqualTo(1);
    assertThat(count("audit_log")).isEqualTo(audits);
  }

  @Test
  void contactReadinessNeverRevealsOwnerAccountOrPrivateCrawlerFallback() throws Exception {
    proof(channel, owner, "TIKTOK_OAUTH_SANDBOX");
    edit(
        owner,
        "{\"contacts\":{\"bookingEmail\":{\"value\":\"private-booking@example.test\"}}}",
        0L,
        200);
    jdbc.update(
        "insert into"
            + " public_creator_metric(id,creator_id,collected_at,freshness_status,niche,content_summary,category,creator_type,data_confidence,contact)"
            + " values(?,?,now(),'FRESH','Beauty','Summary','Beauty','KOC',0.6,'crawler-private@example.test')",
        UUID.randomUUID(),
        channel);
    UUID previewCampaign =
        createCampaign(
            brand,
            jdbc.queryForObject(
                "select brand_profile_id from campaign where id=?", UUID.class, campaign));
    call(
        brand,
        post("/campaigns/" + previewCampaign + "/shortlist"),
        Map.of("creatorId", channel),
        200);
    var result =
        call(
            brand,
            post("/campaigns/" + previewCampaign + "/shortlist/contact-readiness"),
            Map.of("creatorIds", List.of(channel)),
            200);
    assertThat(result.at("/data/items/0/contactChannel").asText()).isEqualTo("IN_APP_PORTAL");
    assertThat(result.toString())
        .doesNotContain(
            owner.getEmail(), "private-booking@example.test", "crawler-private@example.test");
    assertThat(contacts(brand).toString()).doesNotContain("crawler-private@example.test");
  }

  @Test
  void manualClaimApprovalAndRevokeProduceActualVerificationProvenance() throws Exception {
    var publicChannel =
        creators
            .saveAndFlush(
                com.influencermatch.backend.creator.model.Creator.builder()
                    .platform("INSTAGRAM")
                    .externalId(UUID.randomUUID().toString())
                    .userName("manual-public")
                    .build())
            .getId();
    var claim =
        call(
                owner,
                post("/creators/" + publicChannel + "/claim"),
                Map.of(
                    "evidenceUrls",
                    List.of("https://proof.test/claim"),
                    "note",
                    "I manage this channel"),
                201)
            .path("data")
            .path("id")
            .asText();
    var reviewer = account(Role.DATA_MANAGER);
    call(
        reviewer,
        patch("/data/creator-claims/" + claim + "/review"),
        Map.of("decision", "APPROVED", "reason", "Evidence reviewed"),
        200);
    var approved = call(brand, get("/creators/" + publicChannel), null, 200).path("data");
    assertThat(approved.path("verifiedByInfluencerMatch").asBoolean()).isTrue();
    assertThat(approved.at("/verification/method").asText()).isEqualTo("MANUAL_REVIEW");
    assertThat(approved.at("/verification/evidenceSource").asText()).isEqualTo("CREATOR_CLAIM");
    call(
        reviewer,
        post("/data/creator-claims/" + claim + "/revoke"),
        Map.of("reason", "Revoked with evidence"),
        200);
    assertThat(
            call(brand, get("/creators/" + publicChannel), null, 200)
                .at("/data/verifiedByInfluencerMatch")
                .asBoolean())
        .isFalse();
  }

  @Test
  void migrationV30ToV31PreservesOwnershipAndCreatesNoDeclaredProfile() {
    String schema = "portal_" + UUID.randomUUID().toString().replace("-", "");
    org.flywaydb.core.Flyway.configure()
        .dataSource(jdbc.getDataSource())
        .schemas(schema)
        .defaultSchema(schema)
        .target("30")
        .load()
        .migrate();
    try {
      jdbc.update(
          "insert into " + schema + ".\"user\" select * from public.\"user\" where id=?",
          owner.getId());
      jdbc.update(
          "insert into "
              + schema
              + ".creator_master select m.* from public.creator_master m where m.user_id=?",
          owner.getId());
      jdbc.update(
          "insert into " + schema + ".creator select c.* from public.creator c where c.id=?",
          channel);
      var before =
          jdbc.queryForObject(
              "select to_jsonb(c)::text from " + schema + ".creator c where id=?",
              String.class,
              channel);
      assertThat(
              org.flywaydb.core.Flyway.configure()
                  .dataSource(jdbc.getDataSource())
                  .schemas(schema)
                  .defaultSchema(schema)
                  .target("31")
                  .load()
                  .migrate()
                  .migrationsExecuted)
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select to_jsonb(c)::text from " + schema + ".creator c where id=?",
                  String.class,
                  channel))
          .isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + schema + ".creator_portal_profile", Long.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + schema + ".creator_portfolio_item", Long.class))
          .isZero();
    } finally {
      jdbc.execute("drop schema " + schema + " cascade");
    }
  }
}
