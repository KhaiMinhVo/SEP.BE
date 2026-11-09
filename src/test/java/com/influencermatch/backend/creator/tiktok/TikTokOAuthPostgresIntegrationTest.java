package com.influencermatch.backend.creator.tiktok;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.creator.client.provider.TikTokSandboxProofProvider.VerifiedAccount;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.enums.*;
import jakarta.servlet.http.Cookie;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TikTokOAuthPostgresIntegrationTest extends TikTokTestSupport {
  @org.springframework.beans.factory.annotation.Autowired
  com.influencermatch.backend.auth.service.AuthSessionStore loginSessions;

  @Test
  void logoutDuringProviderRequestCannotGrantOwnership() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    UUID sid =
        jdbc.queryForObject(
            "select auth_session_id from tiktok_oauth_session where id=?", UUID.class, s.id());
    String before = businessRows();
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              loginSessions.revoke(sid);
              return new VerifiedAccount("app-scoped-id", "real_username", "Display");
            });
    callback(s, 401);
    assertThat(businessRows()).isEqualTo(before);
  }

  @Test
  void cleanupDuringProviderRequestReturns400WithoutGrant() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              now = now.plusSeconds(600);
              store.expire();
              return new VerifiedAccount("id", "name", "Display");
            });
    assertThat(callback(s, 400).path("code").asText()).isEqualTo("TIKTOK_SESSION_EXPIRED");
    assertThat(row(s.id())).contains("EXPIRED").doesNotContain("Display");
  }

  @Test
  void targetMismatchNeverChangesBusinessData() throws Exception {
    var invitation = invitation(actor.getEmail(), now.plusSeconds(600));
    String before = businessRows();
    var s =
        start(
            Map.of(
                "creatorId",
                channel.getId(),
                "invitationToken",
                "invitation-" + invitation.getId()));
    var result = callback(s, 200);
    assertThat(result.path("data").size()).isEqualTo(2);
    assertThat(result.path("data").path("status").asText()).isEqualTo("READY");
    var view = call(actor, get(SESSIONS + s.id()), null, 200).path("data");
    assertThat(view.path("platform").asText()).isEqualTo("TIKTOK");
    assertThat(view.path("account").path("openId").asText()).isEqualTo("app-scoped-id");
    assertThat(view.path("account").path("username").asText()).isEqualTo("real_username");
    assertThat(view.path("account").path("matchStatus").asText()).isEqualTo("TARGET_MISMATCH");
    assertThat(row(s.id()))
        .doesNotContain(
            s.state(),
            s.cookie().getValue(),
            "real-code",
            "private-secret",
            "access_token",
            "refresh_token");
    assertThat(businessRows()).isEqualTo(before);
    call(actor, post(SESSIONS + s.id() + "/confirm"), Map.of("accountId", "app-scoped-id"), 404);
  }

  @Test
  void replayDoesNotCallProviderOrChangeResult() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    callback(s, 200);
    String before = row(s.id());
    assertThat(callback(s, 409).path("code").asText()).isEqualTo("TIKTOK_ALREADY_PROCESSED");
    assertThat(row(s.id())).isEqualTo(before);
    verify(provider, times(1)).verify("real-code");
  }

  @Test
  void concurrentCallbacksExchangeExactlyOnce() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              entered.countDown();
              assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
              return new VerifiedAccount("id", "name", "Display");
            });
    var pool = Executors.newSingleThreadExecutor();
    try {
      var first = pool.submit(() -> callback(s, 200));
      assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
      callback(s, 409);
      release.countDown();
      first.get(20, TimeUnit.SECONDS);
      verify(provider, times(1)).verify(anyString());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void wrongBrowserDoesNotConsumeState() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    call(null, get(CALLBACK).param("state", s.state()).param("code", "code"), null, 403);
    call(
        null,
        get(CALLBACK)
            .param("state", s.state())
            .param("code", "code")
            .cookie(new Cookie(s.cookie().getName(), "a".repeat(64))),
        null,
        403);
    verifyNoInteractions(provider);
    callback(s, 200);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "invalid", "0000000000000000000000000000000000000000000000000000000000000000"})
  void invalidStateRejected(String state) throws Exception {
    call(null, get(CALLBACK).param("state", state).param("code", "code"), null, 400);
    verifyNoInteractions(provider);
  }

  @Test
  void exactExpiryIsExpiredAndPurgesProfile() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    callback(s, 200);
    now = now.plusSeconds(600);
    assertThat(store.expire()).isPositive();
    assertThat(row(s.id())).contains("EXPIRED").doesNotContain("real_username", "app-scoped-id");
    assertThat(call(actor, get(SESSIONS + s.id()), null, 200).path("data").path("status").asText())
        .isEqualTo("EXPIRED");
    callback(s, 400);
    verify(provider, times(1)).verify(anyString());
  }

  @Test
  void expiredUnconsumedStateNeverCallsProvider() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    now = now.plusSeconds(600);
    callback(s, 400);
    assertThat(row(s.id())).contains("EXPIRED");
    verifyNoInteractions(provider);
  }

  @Test
  void denialConsumesStateAndReturns403() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    call(
        null,
        get(CALLBACK).param("state", s.state()).param("error", "access_denied").cookie(s.cookie()),
        null,
        403);
    assertThat(row(s.id())).contains("FAILED");
    callback(s, 409);
    verifyNoInteractions(provider);
  }

  @Test
  void providerErrorPersistsFailureAndCannotReplay() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    when(provider.verify(anyString()))
        .thenThrow(new BusinessException(ErrorCode.TIKTOK_PROVIDER_ERROR, "TikTok request failed"));
    callback(s, 502);
    assertThat(row(s.id())).contains("FAILED").doesNotContain("app-scoped-id");
    callback(s, 409);
  }

  @Test
  void onlyCreatorWhoStartedCanView() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    call(other, get(SESSIONS + s.id()), null, 404);
    call(null, get(SESSIONS + s.id()), null, 401);
    call(account(Role.BRAND), post(START), Map.of(), 403);
    call(null, post(START), Map.of(), 401);
  }

  @ParameterizedTest
  @ValueSource(strings = {"role", "status", "email", "authVersion", "permission"})
  void changedAccountDuringProviderRequestCannotSaveProof(String change) throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              switch (change) {
                case "role" ->
                    jdbc.update("update \"user\" set role='BRAND' where id=?", actor.getId());
                case "status" ->
                    jdbc.update("update \"user\" set status='DISABLED' where id=?", actor.getId());
                case "email" ->
                    jdbc.update(
                        "update \"user\" set email_verified=false where id=?", actor.getId());
                case "authVersion" ->
                    jdbc.update(
                        "update \"user\" set auth_version=auth_version+1 where id=?",
                        actor.getId());
                case "permission" ->
                    jdbc.update(
                        "insert into role_permission_override(id,role,permission,enabled)"
                            + " values(?,'CREATOR','CLAIM_CREATOR_PROFILE',false)",
                        UUID.randomUUID());
              }
              return new VerifiedAccount("id", "name", "Display");
            });
    try {
      callback(s, 403);
      assertThat(row(s.id())).contains("FAILED").doesNotContain("Display");
    } finally {
      jdbc.update(
          "delete from role_permission_override where role='CREATOR' and"
              + " permission='CLAIM_CREATOR_PROFILE'");
    }
  }

  @Test
  void expiresDuringProviderRequestDoesNotSaveProfile() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              now = now.plusSeconds(600);
              return new VerifiedAccount("id", "name", "Display");
            });
    callback(s, 400);
    assertThat(row(s.id())).contains("EXPIRED").doesNotContain("Display");
  }

  @Test
  void permissionDisabledBeforeStartIsRejected() throws Exception {
    jdbc.update(
        "insert into role_permission_override(id,role,permission,enabled)"
            + " values(?,'CREATOR','CLAIM_CREATOR_PROFILE',false)",
        UUID.randomUUID());
    try {
      call(actor, post(START), Map.of(), 403);
    } finally {
      jdbc.update(
          "delete from role_permission_override where role='CREATOR' and"
              + " permission='CLAIM_CREATOR_PROFILE'");
    }
    verifyNoInteractions(provider);
  }

  @Test
  void startValidatesChannelAndInvitationWithoutConsumption() throws Exception {
    call(actor, post(START), Map.of("creatorId", UUID.randomUUID()), 404);
    channel.setPlatform("INSTAGRAM");
    creators.saveAndFlush(channel);
    call(actor, post(START), Map.of("creatorId", channel.getId()), 400);
    channel.setPlatform("TIKTOK");
    creators.saveAndFlush(channel);
    var invitation = invitation(other.getEmail(), now.plusSeconds(600));
    call(actor, post(START), Map.of("invitationToken", "invitation-" + invitation.getId()), 403);
    invitation.setInvitedEmail(actor.getEmail());
    invitation.setExpiresAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    invitations.saveAndFlush(invitation);
    call(actor, post(START), Map.of("invitationToken", "invitation-" + invitation.getId()), 400);
    assertThat(invitations.findById(invitation.getId()).orElseThrow().getStatus())
        .isEqualTo("PENDING");
    verifyNoInteractions(provider);
  }

  @Test
  void anotherMastersChannelIsRejectedWithoutChangingOwnership() throws Exception {
    var master =
        masters.saveAndFlush(CreatorMaster.builder().user(other).canonicalName("Other").build());
    channel.setMaster(master);
    channel.setSourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED);
    channel.setAuthorizedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    creators.saveAndFlush(channel);
    call(actor, post(START), Map.of("creatorId", channel.getId()), 409);
    assertThat(
            jdbc.queryForObject(
                "select master_id from creator where id=?", UUID.class, channel.getId()))
        .isEqualTo(master.getId());
  }

  @Test
  void invitationChangedDuringProviderRequestDoesNotBecomeProof() throws Exception {
    var invitation = invitation(actor.getEmail(), now.plusSeconds(600));
    var s = start(Map.of("invitationToken", "invitation-" + invitation.getId()));
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              jdbc.update(
                  "update creator_invitation set status='ACCEPTED' where id=?", invitation.getId());
              return new VerifiedAccount("id", "name", "Display");
            });
    callback(s, 400);
    assertThat(row(s.id())).contains("FAILED").doesNotContain("Display");
  }

  @Test
  void missingConfigurationReturns503WithoutCreatingOrConsumingSession() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    long before = jdbc.queryForObject("select count(*) from tiktok_oauth_session", Long.class);
    doThrow(
            new BusinessException(
                ErrorCode.TIKTOK_OAUTH_UNAVAILABLE, "TikTok Sandbox Login is not configured"))
        .when(config)
        .requireConfigured();
    call(actor, post(START), Map.of(), 503);
    callback(s, 503);
    assertThat(row(s.id())).contains("NEW");
    assertThat(jdbc.queryForObject("select count(*) from tiktok_oauth_session", Long.class))
        .isEqualTo(before);
    verifyNoInteractions(provider);
  }

  @Test
  void pendingInvitationEmailCannotBeBypassedByGeneralStart() throws Exception {
    invitation(other.getEmail(), now.plusSeconds(600));
    call(actor, post(START), Map.of("creatorId", channel.getId()), 403);
    verifyNoInteractions(provider);
  }

  @Test
  void callbackRevalidatesInvitationExpiryBeforeProvider() throws Exception {
    var invitation = invitation(actor.getEmail(), now.plusSeconds(60));
    var s = start(Map.of("invitationToken", "invitation-" + invitation.getId()));
    now = now.plusSeconds(60);
    callback(s, 400);
    assertThat(row(s.id())).contains("FAILED");
    verifyNoInteractions(provider);
  }

  @Test
  void sameMasterWrongResolvedIdNeverReverifies() throws Exception {
    var master =
        masters.saveAndFlush(CreatorMaster.builder().user(actor).canonicalName("Existing").build());
    channel.setMaster(master);
    channel.setSourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED);
    channel.setAuthorizedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    creators.saveAndFlush(channel);
    String before = businessRows();
    var s = start(Map.of("creatorId", channel.getId()));
    callback(s, 200);
    assertThat(
            call(actor, get(SESSIONS + s.id()), null, 200)
                .path("data")
                .path("account")
                .path("matchStatus")
                .asText())
        .isEqualTo("TARGET_MISMATCH");
    assertThat(businessRows()).isEqualTo(before);
  }

  @Test
  void cleanupDoesNotChangeOtherSessionsOrBusinessData() throws Exception {
    var first = start(Map.of("creatorId", channel.getId()));
    callback(first, 200);
    now = now.plusSeconds(300);
    var second = start(Map.of("creatorId", channel.getId()));
    String before = businessRows();
    now = now.plusSeconds(300);
    store.expire();
    assertThat(row(first.id())).contains("EXPIRED").doesNotContain("real_username");
    assertThat(row(second.id())).contains("NEW");
    assertThat(businessRows()).isEqualTo(before);
  }

  @Test
  void callbackRejectsChangedConfigurationBeforeSavingProof() throws Exception {
    var s = start(Map.of("creatorId", channel.getId()));
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              doReturn("changed-app").when(config).clientKey();
              return new VerifiedAccount("id", "name", "Display");
            });
    callback(s, 503);
    assertThat(row(s.id())).contains("FAILED").doesNotContain("Display");
  }

  @Test
  void migrationV22AddsOnlyProofSessionAndPreservesExistingData() {
    String schema = "tt_" + UUID.randomUUID().toString().replace("-", "");
    var old =
        Flyway.configure()
            .dataSource(jdbc.getDataSource())
            .schemas(schema)
            .defaultSchema(schema)
            .target("21")
            .load();
    old.migrate();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into "
            + schema
            + ".creator(id,platform,external_id,user_name,created_at,updated_at)"
            + " values(?,'TIKTOK','unchanged','history',now(),now())",
        id);
    String before =
        jdbc.queryForObject(
            "select to_jsonb(c)::text from " + schema + ".creator c where id=?", String.class, id);
    var upgrade =
        Flyway.configure()
            .dataSource(jdbc.getDataSource())
            .schemas(schema)
            .defaultSchema(schema)
            .target("22")
            .load();
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select to_jsonb(c)::text from " + schema + ".creator c where id=?",
                String.class,
                id))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema=? and"
                    + " table_name='tiktok_oauth_session' and column_name like '%token%'",
                Integer.class, schema))
        .isZero();
    jdbc.execute("drop schema " + schema + " cascade");
  }
}
