package com.influencermatch.backend.creator.tiktok;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.service.CreatorIngestionService;
import com.influencermatch.backend.security.permission.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TikTokCrawlerIdentityPostgresIntegrationTest extends TikTokTestSupport {
  @Autowired CreatorIngestionService ingestion;

  @BeforeEach
  void uniqueProviderIdentity() {
    String openId = "new-channel-" + UUID.randomUUID();
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new com.influencermatch.backend.creator.client.provider
                  .TikTokSandboxProofProvider.VerifiedAccount(openId, "real_username", "Real name");
            });
  }

  String newId() {
    return "9" + channel.getExternalId();
  }

  @Test
  void createsMinimalProfileAndLaterIngestionUpdatesSameUuid() throws Exception {
    var data = callback(start(Map.of()), 200).path("data");
    UUID id = UUID.fromString(data.path("creatorId").asText());
    assertThat(data.path("status").asText()).isEqualTo("AUTHORIZED");
    var created = creators.findById(id).orElseThrow();
    assertThat(created.getExternalId()).isEqualTo(newId());
    assertThat(created.getIsStableId()).isTrue();
    assertThat(created.getProfileUrl()).isEqualTo("https://www.tiktok.com/@real_username");
    assertThat(created.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public_creator_metric where creator_id=?", Long.class, id))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select embedding is null from creator where id=?", Boolean.class, id))
        .isTrue();
    var profile = call(actor, get("/creators/" + id), null, 200).path("data");
    assertThat(profile.path("verifiedByInfluencerMatch").asBoolean()).isTrue();
    assertThat(profile.path("followers").isNull() || profile.path("followers").isMissingNode())
        .isTrue();
    ingest(newId(), "renamed_creator");
    assertThat(creators.findByPlatformAndExternalId("TIKTOK", newId()).orElseThrow().getId())
        .isEqualTo(id);
    assertThat(creators.findById(id).orElseThrow().getSourceType())
        .isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator where platform='TIKTOK' and external_id=?",
                Long.class,
                newId()))
        .isEqualTo(1);
  }

  void ingest(String id, String name) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("SERVICE_INGEST_CREATOR"))));
    try {
      ingestion.ingestCreatorData(
          CreatorIngestionRequest.builder()
              .platform("TIKTOK")
              .externalId(id)
              .userName(name)
              .creatorType("KOC")
              .followers(10000L)
              .build());
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void renamedChannelUsesAuthorIdRatherThanStoredUsername() throws Exception {
    when(crawler.resolveTikTokIdentity(anyString()))
        .thenAnswer(i -> resolved(channel.getExternalId(), i.getArgument(0)));
    var data = callback(start(Map.of()), 200).path("data");
    assertThat(data.path("creatorId").asText()).isEqualTo(channel.getId().toString());
    assertThat(creators.findById(channel.getId()).orElseThrow().getUserName())
        .isEqualTo("real_username");
    assertThat(creators.findById(channel.getId()).orElseThrow().getExternalId())
        .isEqualTo(channel.getExternalId());
  }

  @Test
  void unstableStoredIdIsRejectedAndAudited() throws Exception {
    channel.setIsStableId(false);
    creators.saveAndFlush(channel);
    when(crawler.resolveTikTokIdentity(anyString()))
        .thenAnswer(i -> resolved(channel.getExternalId(), i.getArgument(0)));
    String before = businessRows();
    callback(start(Map.of()), 409);
    assertThat(businessRows()).isEqualTo(before);
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
  void invitationTargetMismatchCannotCreateAnotherChannel() throws Exception {
    var inv = invitation(actor.getEmail(), now.plusSeconds(600));
    String before = businessRows();
    var s = start(Map.of("invitationToken", "invitation-" + inv.getId()));
    assertThat(callback(s, 200).path("data").path("status").asText()).isEqualTo("READY");
    assertThat(row(s.id())).contains("TARGET_MISMATCH");
    assertThat(businessRows()).isEqualTo(before);
    assertThat(invitations.findById(inv.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "stale", "username", "permission", "version", "expired"})
  void resolverFailuresOrChangedSessionNeverCreateChannel(String change) throws Exception {
    var s = start(Map.of());
    String before = businessRows();
    when(crawler.resolveTikTokIdentity(anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var result = resolved(newId(), i.getArgument(0));
              switch (change) {
                case "null" -> {
                  return null;
                }
                case "stale" -> result.setObservedAt(now.minusSeconds(91));
                case "username" -> result.setResolvedUsername("wrong_name");
                case "permission" ->
                    jdbc.update(
                        "insert into role_permission_override(id,role,permission,enabled)"
                            + " values(?,'CREATOR','CLAIM_CREATOR_PROFILE',false)",
                        UUID.randomUUID());
                case "version" ->
                    jdbc.update(
                        "update \"user\" set auth_version=auth_version+1 where id=?",
                        actor.getId());
                case "expired" -> {
                  now = now.plusSeconds(600);
                  result.setObservedAt(now);
                }
              }
              return result;
            });
    try {
      int expected =
          Set.of("permission", "version").contains(change)
              ? 403
              : change.equals("expired") ? 400 : 502;
      callback(s, expected);
      assertThat(creators.findByPlatformAndExternalId("TIKTOK", newId())).isEmpty();
      if (!change.equals("version")) assertThat(businessRows()).isEqualTo(before);
      callback(s, change.equals("expired") ? 400 : 409);
      verify(crawler, times(1)).resolveTikTokIdentity(anyString());
    } finally {
      jdbc.update(
          "delete from role_permission_override where role='CREATOR' and"
              + " permission='CLAIM_CREATOR_PROFILE'");
    }
  }

  @Test
  void auditFailureRollsBackNewChannelAndMaster() throws Exception {
    var s = start(Map.of());
    String before = businessRows();
    String fn = "tt_new_fail_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "create function "
            + fn
            + "() returns trigger language plpgsql as $$ begin if NEW.performed_by='"
            + actor.getId()
            + "'::uuid then raise exception 'test rejected'; end if; return NEW; end $$");
    jdbc.execute(
        "create trigger "
            + fn
            + " before insert on creator_identity_audit for each row execute function "
            + fn
            + "()");
    try {
      callback(s, 500);
      assertThat(businessRows()).isEqualTo(before);
      assertThat(creators.findByPlatformAndExternalId("TIKTOK", newId())).isEmpty();
      assertThat(row(s.id())).contains("FAILED");
    } finally {
      jdbc.execute("drop trigger " + fn + " on creator_identity_audit");
      jdbc.execute("drop function " + fn + "()");
    }
  }

  @Test
  void observationExpiringWhileWaitingForLocksCannotCreateChannel() throws Exception {
    var s = start(Map.of());
    var resolved = new java.util.concurrent.atomic.AtomicBoolean();
    when(crawler.resolveTikTokIdentity(anyString()))
        .thenAnswer(
            i -> {
              var result = resolved(newId(), i.getArgument(0));
              resolved.set(true);
              return result;
            });
    when(clock.instant())
        .thenAnswer(
            i ->
                resolved.get() && TransactionSynchronizationManager.isActualTransactionActive()
                    ? now.plusSeconds(91)
                    : now);
    assertThat(callback(s, 502).path("code").asText())
        .isEqualTo("TIKTOK_IDENTITY_RESOLUTION_FAILED");
    assertThat(creators.findByPlatformAndExternalId("TIKTOK", newId())).isEmpty();
  }

  @Test
  void twoCreatorsRacingToCreateAbsentChannelHaveOnlyOneOwner() throws Exception {
    var first = start(Map.of());
    var original = actor;
    actor = other;
    Started second;
    try {
      second = start(Map.of());
    } finally {
      actor = original;
    }
    var both = new CountDownLatch(2);
    when(crawler.resolveTikTokIdentity(anyString()))
        .thenAnswer(
            i -> {
              both.countDown();
              assertThat(both.await(20, TimeUnit.SECONDS)).isTrue();
              return resolved(newId(), i.getArgument(0));
            });
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a =
          pool.submit(
              () ->
                  mvc.perform(
                          get(CALLBACK)
                              .param("state", first.state())
                              .param("code", "first")
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
                              .param("code", "second")
                              .cookie(second.cookie()))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      var created = creators.findByPlatformAndExternalId("TIKTOK", newId()).orElseThrow();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from tiktok_account_binding where creator_id=?",
                  Long.class,
                  created.getId()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from creator_identity_audit where account_id=?",
                  Long.class,
                  created.getId()))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void ingestionAndOauthRacingToCreateOneIdKeepSingleChannel() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(crawler.resolveTikTokIdentity(anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              entered.countDown();
              assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
              return resolved(newId(), i.getArgument(0));
            });
    var s = start(Map.of());
    var pool = Executors.newSingleThreadExecutor();
    try {
      var oauth = pool.submit(() -> callback(s, 200));
      assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
      ingest(newId(), "from_crawler");
      UUID id = creators.findByPlatformAndExternalId("TIKTOK", newId()).orElseThrow().getId();
      release.countDown();
      assertThat(oauth.get(20, TimeUnit.SECONDS).path("data").path("creatorId").asText())
          .isEqualTo(id.toString());
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from creator where platform='TIKTOK' and external_id=?",
                  Long.class,
                  newId()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from tiktok_account_binding where creator_id=?", Long.class, id))
          .isEqualTo(1);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }
}
