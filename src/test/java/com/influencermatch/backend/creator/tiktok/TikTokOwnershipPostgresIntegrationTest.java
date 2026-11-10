package com.influencermatch.backend.creator.tiktok;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.creator.client.provider.TikTokSandboxProofProvider.VerifiedAccount;
import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.service.CreatorIngestionService;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.*;

class TikTokOwnershipPostgresIntegrationTest extends TikTokTestSupport {
  @Autowired TikTokAccountBindingRepository bindings;
  @Autowired TikTokOwnershipService ownership;
  @Autowired CreatorIngestionService ingestion;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  @Autowired
  com.influencermatch.backend.creator.repository.CreatorProviderIdentityRepository identities;

  String username, openId;

  @Test
  void generalStartCannotBypassInvitationEmail() throws Exception {
    var inv = invitation(other.getEmail(), now.plusSeconds(600));
    String before = businessRows();
    var s = start(Map.of());
    callback(s, 403);
    assertThat(businessRows()).isEqualTo(before);
    assertThat(count("tiktok_account_binding")).isZero();
    assertThat(invitations.findById(inv.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
  }

  @Test
  void completedSessionKeepsOnlyResultAfterExpiry() throws Exception {
    var s = start(Map.of());
    callback(s, 200);
    now = now.plusSeconds(600);
    store.expire();
    var view = call(actor, get(SESSIONS + s.id()), null, 200).path("data");
    assertThat(view.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(view.path("creatorId").asText()).isEqualTo(channel.getId().toString());
    assertThat(row(s.id())).doesNotContain(openId, username, "Real profile");
    String completed = row(s.id());
    callback(s, 409);
    assertThat(row(s.id())).isEqualTo(completed);
    assertThat(successAudits()).isEqualTo(1);
  }

  @Test
  void ingestionRenameDuringOAuthStillUsesStableId() throws Exception {
    var s = start(Map.of());
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              ingest("renamed_" + username);
              return new VerifiedAccount(openId, username, "Name");
            });
    callback(s, 200);
    assertThat(row(s.id())).contains("COMPLETED");
    assertThat(creators.findById(channel.getId()).orElseThrow().getMaster()).isNotNull();
    assertThat(creators.findById(channel.getId()).orElseThrow().getUserName()).isEqualTo(username);
    assertThat(count("tiktok_account_binding")).isEqualTo(1);
  }

  void ingest(String newUsername) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("SERVICE_INGEST_CREATOR"))));
    try {
      ingestion.ingestCreatorData(
          CreatorIngestionRequest.builder()
              .platform("TIKTOK")
              .externalId(channel.getExternalId())
              .userName(newUsername)
              .creatorType("KOC")
              .followers(10000L)
              .niche("Gaming")
              .category("Gaming")
              .contentSummary("Profile")
              .dataConfidence(new java.math.BigDecimal("0.8"))
              .build());
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void concurrentIngestionAndGrantShareTheUsernameGate() throws Exception {
    var s = start(Map.of());
    var renamed = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var providerReturned = new CountDownLatch(1);
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              providerReturned.countDown();
              return new VerifiedAccount(openId, username, "Name");
            });
    var pool = Executors.newFixedThreadPool(2);
    try {
      var crawl =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          tx -> {
                            ingest("renamed_" + username);
                            renamed.countDown();
                            try {
                              assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException ex) {
                              Thread.currentThread().interrupt();
                              throw new RuntimeException(ex);
                            }
                            return null;
                          }));
      assertThat(renamed.await(20, TimeUnit.SECONDS)).isTrue();
      Boolean gateAvailable =
          new TransactionTemplate(transactions)
              .execute(
                  tx ->
                      jdbc.queryForObject(
                          "select pg_try_advisory_xact_lock(hashtext(?))",
                          Boolean.class,
                          TikTokUsernames.MATCH_LOCK));
      assertThat(gateAvailable).isFalse();
      var oauth = pool.submit(() -> callback(s, 200));
      assertThat(providerReturned.await(20, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      crawl.get(20, TimeUnit.SECONDS);
      assertThat(oauth.get(20, TimeUnit.SECONDS).path("data").path("status").asText())
          .isEqualTo("AUTHORIZED");
      assertThat(row(s.id())).contains("COMPLETED");
      assertThat(count("tiktok_account_binding")).isEqualTo(1);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @BeforeEach
  void matchingProfile() {
    username = "user_" + UUID.randomUUID().toString().replace("-", "");
    openId = "oid_" + UUID.randomUUID();
    channel.setUserName(" \t@" + username.toUpperCase(Locale.ROOT) + "\r\n");
    creators.saveAndFlush(channel);
    when(crawler.resolveTikTokIdentity(anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return resolved(channel.getExternalId(), i.getArgument(0));
            });
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new VerifiedAccount(openId, username, "Real profile");
            });
  }

  Started startFor(User user, Map<String, Object> body) throws Exception {
    User original = actor;
    try {
      actor = user;
      return start(body);
    } finally {
      actor = original;
    }
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where creator_id=?", Long.class, channel.getId());
  }

  long successAudits() {
    return jdbc.queryForObject(
        "select count(*) from creator_identity_audit where account_id=? and event_type in"
            + " ('LINK_TIKTOK_ACCOUNT','REFRESH_TIKTOK_VERIFICATION')",
        Long.class,
        channel.getId());
  }

  @Test
  void grantsMatchingChannelAndPreservesCrawlerIdAndInvitationAtomicity() throws Exception {
    var inv = invitation(actor.getEmail(), now.plusSeconds(600));
    var externalId = channel.getExternalId();
    long userCount = users.count();
    var s =
        start(Map.of("creatorId", channel.getId(), "invitationToken", "invitation-" + inv.getId()));
    var result = callback(s, 200).path("data");
    assertThat(result.path("status").asText()).isEqualTo("AUTHORIZED");
    assertThat(result.path("creatorId").asText()).isEqualTo(channel.getId().toString());
    UUID master = UUID.fromString(result.path("masterId").asText());
    assertThat(
            jdbc.queryForObject(
                "select master_id from creator where id=?", UUID.class, channel.getId()))
        .isEqualTo(master);
    assertThat(
            jdbc.queryForObject(
                "select source_type from creator where id=?", String.class, channel.getId()))
        .isEqualTo("AUTHORIZED");
    assertThat(creators.findById(channel.getId()).orElseThrow().getExternalId())
        .isEqualTo(externalId);
    assertThat(users.count()).isEqualTo(userCount);
    assertThat(
            call(actor, get("/creators/" + channel.getId()), null, 200)
                .path("data")
                .path("verifiedByInfluencerMatch")
                .asBoolean())
        .isTrue();
    assertThat(invitations.findById(inv.getId()).orElseThrow().getStatus()).isEqualTo("ACCEPTED");
    assertThat(count("tiktok_account_binding")).isEqualTo(1);
    assertThat(count("creator_account_verification")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select verification_method from creator_account_verification where creator_id=?",
                String.class,
                channel.getId()))
        .isEqualTo("TIKTOK_OAUTH_SANDBOX");
    assertThat(row(s.id())).contains("COMPLETED").doesNotContain(openId, "Real profile", username);
    var view = call(actor, get(SESSIONS + s.id()), null, 200).path("data");
    assertThat(view.path("creatorId").asText()).isEqualTo(channel.getId().toString());
    assertThat(view.path("status").asText()).isEqualTo("COMPLETED");
    callback(s, 409);
    assertThat(successAudits()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select details->>'matchMethod' from creator_identity_audit where account_id=?",
                String.class,
                channel.getId()))
        .isEqualTo("CRAWLER_AUTHOR_ID");
  }

  @Test
  void reusesExistingMasterAndRefreshesOneVerification() throws Exception {
    var master =
        masters.saveAndFlush(CreatorMaster.builder().user(actor).canonicalName("Existing").build());
    var facebook =
        creators.saveAndFlush(
            Creator.builder()
                .platform("FACEBOOK")
                .externalId(UUID.randomUUID().toString())
                .userName("FB")
                .master(master)
                .sourceType(CreatorSourceType.AUTHORIZED)
                .authorizedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .build());
    String before =
        jdbc.queryForObject(
            "select to_jsonb(c)::text from creator c where id=?", String.class, facebook.getId());
    for (int i = 0; i < 2; i++) {
      var result = callback(start(Map.of()), 200).path("data");
      assertThat(result.path("masterId").asText()).isEqualTo(master.getId().toString());
    }
    assertThat(count("tiktok_account_binding")).isEqualTo(1);
    assertThat(count("creator_account_verification")).isEqualTo(1);
    assertThat(successAudits()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select to_jsonb(c)::text from creator c where id=?",
                String.class,
                facebook.getId()))
        .isEqualTo(before);
  }

  @Test
  void differentPlatformOrDisplayNameNeverMatches() throws Exception {
    channel.setPlatform("INSTAGRAM");
    creators.saveAndFlush(channel);
    var otherChannel =
        creators.saveAndFlush(
            Creator.builder()
                .platform("TIKTOK")
                .externalId(UUID.randomUUID().toString())
                .userName("other_" + UUID.randomUUID())
                .displayName(username)
                .build());
    var result = callback(start(Map.of()), 200).path("data");
    UUID created = UUID.fromString(result.path("creatorId").asText());
    assertThat(result.path("status").asText()).isEqualTo("AUTHORIZED");
    assertThat(created).isNotEqualTo(channel.getId()).isNotEqualTo(otherChannel.getId());
    assertThat(creators.findById(created).orElseThrow().getPlatform()).isEqualTo("TIKTOK");
    assertThat(creators.findById(created).orElseThrow().getExternalId())
        .isEqualTo(channel.getExternalId());
    assertThat(creators.findById(otherChannel.getId()).orElseThrow().getMaster()).isNull();
    assertThat(successAudits()).isZero();
  }

  @Test
  void wrongTargetRemainsReadyWithoutBusinessWrites() throws Exception {
    var target =
        creators.saveAndFlush(
            Creator.builder()
                .platform("TIKTOK")
                .externalId(UUID.randomUUID().toString())
                .userName("other_" + UUID.randomUUID())
                .build());
    String before = businessRows();
    var s = start(Map.of("creatorId", target.getId()));
    callback(s, 200);
    assertThat(row(s.id())).contains("TARGET_MISMATCH");
    assertThat(businessRows()).isEqualTo(before);
    assertThat(count("tiktok_account_binding")).isZero();
  }

  @Test
  void duplicateUsernamesDoNotOverrideResolvedStableId() throws Exception {
    var duplicate =
        creators.saveAndFlush(
            Creator.builder()
                .platform("TIKTOK")
                .externalId("88888888888888888888")
                .userName(username)
                .build());
    var result = callback(start(Map.of()), 200).path("data");
    assertThat(result.path("creatorId").asText()).isEqualTo(channel.getId().toString());
    assertThat(creators.findById(duplicate.getId()).orElseThrow().getMaster()).isNull();
  }

  @Test
  void foreignOwnerReturns409AndCommitsRejectionAudit() throws Exception {
    var s = start(Map.of());
    var master =
        masters.saveAndFlush(CreatorMaster.builder().user(other).canonicalName("Other").build());
    channel.setMaster(master);
    channel.setSourceType(CreatorSourceType.AUTHORIZED);
    channel.setAuthorizedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    creators.saveAndFlush(channel);
    String before = businessRows();
    callback(s, 409);
    assertThat(businessRows()).isEqualTo(before);
    assertThat(row(s.id())).contains("FAILED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where entity_id=? and user_id=? and"
                    + " action='REJECT_CREATOR_INVITATION_OR_OWNERSHIP'",
                Long.class,
                channel.getId(),
                actor.getId()))
        .isEqualTo(1);
  }

  @Test
  void mismatchedBindingCannotBeOverwritten() throws Exception {
    var previous =
        creators.saveAndFlush(
            Creator.builder()
                .platform("TIKTOK")
                .externalId(UUID.randomUUID().toString())
                .userName("previous_" + UUID.randomUUID())
                .build());
    bindings.saveAndFlush(
        TikTokAccountBinding.builder()
            .environment("SANDBOX")
            .clientKey("sandbox-key")
            .openId(openId)
            .creator(previous)
            .username("previous")
            .verifiedAt(now)
            .build());
    var s = start(Map.of());
    callback(s, 409);
    assertThat(creators.findById(channel.getId()).orElseThrow().getMaster()).isNull();
    assertThat(
            bindings
                .findByEnvironmentAndClientKeyAndOpenId("SANDBOX", "sandbox-key", openId)
                .orElseThrow()
                .getCreator()
                .getId())
        .isEqualTo(previous.getId());
    assertThat(successAudits()).isZero();
  }

  @Test
  void anotherOpenIdCannotReplaceExistingChannelBinding() throws Exception {
    callback(start(Map.of()), 200);
    String before = businessRows();
    when(provider.verify(anyString()))
        .thenReturn(new VerifiedAccount("different-openid", username, "Name"));
    callback(start(Map.of()), 409);
    assertThat(businessRows()).isEqualTo(before);
    assertThat(successAudits()).isEqualTo(1);
  }

  @Test
  void bindingIsScopedToAppAndDoesNotTrustLegacyIdentity() throws Exception {
    bindings.saveAndFlush(
        TikTokAccountBinding.builder()
            .environment("SANDBOX")
            .clientKey("other-app")
            .openId(openId)
            .creator(channel)
            .username(username)
            .verifiedAt(now)
            .build());
    callback(start(Map.of()), 200);
    assertThat(count("tiktok_account_binding")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_provider_identity where creator_id=?",
                Long.class,
                channel.getId()))
        .isZero();
  }

  @Test
  void auditFailureRollsBackMasterBindingTickAndInvitation() throws Exception {
    var inv = invitation(actor.getEmail(), now.plusSeconds(600));
    var s = start(Map.of("invitationToken", "invitation-" + inv.getId()));
    String before = businessRows();
    String fn = "fail_tt_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "create function "
            + fn
            + "() returns trigger language plpgsql as $$ begin if NEW.account_id='"
            + channel.getId()
            + "'::uuid then raise exception 'test audit rejected'; end if; return NEW; end $$");
    jdbc.execute(
        "create trigger "
            + fn
            + " before insert on creator_identity_audit for each row execute function "
            + fn
            + "()");
    try {
      callback(s, 500);
      assertThat(businessRows()).isEqualTo(before);
      assertThat(count("tiktok_account_binding")).isZero();
      assertThat(row(s.id())).contains("FAILED");
    } finally {
      jdbc.execute("drop trigger " + fn + " on creator_identity_audit");
      jdbc.execute("drop function " + fn + "()");
    }
  }

  @Test
  void concurrentCallbacksHaveOneGrantAndOneReplay() throws Exception {
    var s = start(Map.of());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              entered.countDown();
              assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
              return new VerifiedAccount(openId, username, "Name");
            });
    var pool = Executors.newSingleThreadExecutor();
    try {
      var first = pool.submit(() -> callback(s, 200));
      assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
      callback(s, 409);
      release.countDown();
      assertThat(first.get(20, TimeUnit.SECONDS).path("data").path("status").asText())
          .isEqualTo("AUTHORIZED");
      assertThat(successAudits()).isEqualTo(1);
      assertThat(count("tiktok_account_binding")).isEqualTo(1);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void twoCreatorsRacingForOneChannelHaveOneOwner() throws Exception {
    var first = start(Map.of());
    var second = startFor(other, Map.of());
    var both = new CountDownLatch(2);
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              both.countDown();
              assertThat(both.await(20, TimeUnit.SECONDS)).isTrue();
              return new VerifiedAccount(openId, username, "Name");
            });
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a =
          pool.submit(
              () ->
                  mvc.perform(
                          get(CALLBACK)
                              .param("state", first.state())
                              .param("code", "code")
                              .cookie(first.cookie()))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      var b =
          pool.submit(
              () ->
                  mvc.perform(
                          get(CALLBACK)
                              .param("state", second.state())
                              .param("code", "code")
                              .cookie(second.cookie()))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(successAudits()).isEqualTo(1);
      assertThat(count("tiktok_account_binding")).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void sameCreatorTwoChannelsShareOneMaster() throws Exception {
    var second =
        creators.saveAndFlush(
            Creator.builder()
                .platform("TIKTOK")
                .externalId(UUID.randomUUID().toString())
                .userName("second_" + username)
                .build());
    second.setExternalId("8" + channel.getExternalId());
    creators.saveAndFlush(second);
    when(crawler.resolveTikTokIdentity(anyString()))
        .thenAnswer(
            i ->
                resolved(
                    username.equals(i.getArgument(0))
                        ? channel.getExternalId()
                        : second.getExternalId(),
                    i.getArgument(0)));
    var a = start(Map.of("creatorId", channel.getId()));
    var b = start(Map.of("creatorId", second.getId()));
    var barrier = new CountDownLatch(2);
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              boolean first = "first-code".equals(i.getArgument(0));
              barrier.countDown();
              assertThat(barrier.await(20, TimeUnit.SECONDS)).isTrue();
              return new VerifiedAccount(
                  first ? openId : "second_" + openId,
                  first ? username : "second_" + username,
                  "Name");
            });
    var pool = Executors.newFixedThreadPool(2);
    try {
      var resultA =
          pool.submit(
              () ->
                  call(
                      null,
                      get(CALLBACK)
                          .param("state", a.state())
                          .param("code", "first-code")
                          .cookie(a.cookie()),
                      null,
                      200));
      var resultB =
          pool.submit(
              () ->
                  call(
                      null,
                      get(CALLBACK)
                          .param("state", b.state())
                          .param("code", "second-code")
                          .cookie(b.cookie()),
                      null,
                      200));
      var r1 = resultA.get(20, TimeUnit.SECONDS).path("data");
      var r2 = resultB.get(20, TimeUnit.SECONDS).path("data");
      assertThat(r1.path("status").asText()).isEqualTo("AUTHORIZED");
      assertThat(r2.path("status").asText()).isEqualTo("AUTHORIZED");
      assertThat(r1.path("masterId").asText()).isEqualTo(r2.path("masterId").asText());
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from creator_master where user_id=?", Long.class, actor.getId()))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void oldReadySessionCannotGrantOwnershipAfterUpgrade() throws Exception {
    var s = start(Map.of());
    jdbc.update(
        "update tiktok_oauth_session set status='READY',open_id=?,username=?,display_name='Old"
            + " proof' where id=?",
        openId,
        username,
        s.id());
    String before = businessRows();
    callback(s, 409);
    assertThat(businessRows()).isEqualTo(before);
    assertThat(count("tiktok_account_binding")).isZero();
    verifyNoInteractions(provider);
    assertThat(
            call(actor, get(SESSIONS + s.id()), null, 200)
                .path("data")
                .path("account")
                .path("matchStatus")
                .asText())
        .isEqualTo("UNMATCHED");
  }

  @Test
  void legacyMockIdentityCannotBypassResolverFailure() throws Exception {
    identities.saveAndFlush(
        CreatorProviderIdentity.builder()
            .creator(channel)
            .provider("TIKTOK")
            .providerUserId(openId)
            .providerUsername(username)
            .rawProfileData(Map.of("verificationEnvironment", "MOCK_FALLBACK"))
            .build());
    channel.setUserName("renamed_" + UUID.randomUUID());
    creators.saveAndFlush(channel);
    String before = businessRows();
    when(crawler.resolveTikTokIdentity(anyString())).thenReturn(null);
    var s = start(Map.of());
    assertThat(callback(s, 502).path("code").asText())
        .isEqualTo("TIKTOK_IDENTITY_RESOLUTION_FAILED");
    assertThat(businessRows()).isEqualTo(before);
    assertThat(count("tiktok_account_binding")).isZero();
    assertThat(row(s.id())).contains("FAILED");
  }

  @Test
  void sqlNormalizationMatchesJavaIncludingWhitespaceAndSingleAt() {
    for (String raw : List.of(" \t@MiXi\r\n", "@@mixi", "MiXi", " @ ", "\u0001@Mixi\u001f")) {
      assertThat(jdbc.queryForObject("select im_tiktok_username(?)", String.class, raw))
          .isEqualTo(TikTokUsernames.normalize(raw));
    }
  }

  @Test
  void migrationV23PreservesV22ProofAndCreatorWithoutGranting() {
    String schema = "tt_owner_" + UUID.randomUUID().toString().replace("-", "");
    var old =
        org.flywaydb.core.Flyway.configure()
            .dataSource(jdbc.getDataSource())
            .schemas(schema)
            .defaultSchema(schema)
            .target("22")
            .load();
    old.migrate();
    jdbc.update(
        "insert into " + schema + ".\"user\" select * from public.\"user\" where id=?",
        actor.getId());
    jdbc.update(
        "insert into " + schema + ".creator select * from public.creator where id=?",
        channel.getId());
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into "
            + schema
            + ".tiktok_oauth_session(id,user_id,auth_version,client_key,state_hash,browser_binding_hash,status,expires_at,open_id,username,display_name,created_at,updated_at)"
            + " values(?,?,0,'sandbox-key',?,?,'READY',now()+interval '10 minutes',?,?,'Old"
            + " profile',now(),now())",
        id,
        actor.getId(),
        "f".repeat(64),
        "a".repeat(64),
        openId,
        username);
    String before =
        jdbc.queryForObject(
            "select to_jsonb(c)::text from " + schema + ".creator c where id=?",
            String.class,
            channel.getId());
    try {
      var upgraded =
          org.flywaydb.core.Flyway.configure()
              .dataSource(jdbc.getDataSource())
              .schemas(schema)
              .defaultSchema(schema)
              .target("23")
              .load();
      assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select to_jsonb(c)::text from " + schema + ".creator c where id=?",
                  String.class,
                  channel.getId()))
          .isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + schema + ".tiktok_account_binding", Long.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select status from " + schema + ".tiktok_oauth_session where id=?",
                  String.class,
                  id))
          .isEqualTo("READY");
      assertThat(
              jdbc.queryForObject(
                  "select linked_creator_id from " + schema + ".tiktok_oauth_session where id=?",
                  UUID.class,
                  id))
          .isNull();
    } finally {
      jdbc.execute("drop schema " + schema + " cascade");
    }
  }
}
