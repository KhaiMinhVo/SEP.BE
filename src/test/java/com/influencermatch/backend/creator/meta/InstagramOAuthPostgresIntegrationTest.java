package com.influencermatch.backend.creator.meta;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.enums.Role;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstagramOAuthPostgresIntegrationTest extends DirectInstagramTestSupport {
  @Test
  void worksWithoutFacebookAndPurgesSecretsAfterGrantAndReplay() throws Exception {
    var s = readyDirect();
    var view = call(creator, get(path(s)), null, 200).path("data");
    assertThat(view.path("oauthFlow").asText()).isEqualTo("INSTAGRAM_LOGIN");
    assertThat(view.path("accounts").get(0).has("pageId")).isFalse();
    assertThat(view.toString()).doesNotContain("private-ig-token", "subject", "tokenExpiresAt");
    assertThat(confirm(s, channel.getExternalId(), 200).path("data").path("status").asText())
        .isEqualTo("AUTHORIZED");
    var rows = verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().getVerificationMethod()).isEqualTo("INSTAGRAM_OAUTH");
    assertThat(rows.getFirst().getAccessTokenEncrypted()).isNull();
    assertThat(rows.getFirst().getRefreshTokenEncrypted()).isNull();
    assertThat(sessions.findById(s.id()).orElseThrow().getPayloadCiphertext()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select raw_profile_data::text from creator_provider_identity where creator_id=?",
                String.class,
                channel.getId()))
        .contains("INSTAGRAM_LOGIN")
        .doesNotContain("pageId", "private-ig-token");
    assertThat(confirm(s, channel.getExternalId(), 409).path("code").asText())
        .isEqualTo("META_OAUTH_ALREADY_PROCESSED");
    verify(instagram, times(1)).verify(any());
    verifyNoInteractions(facebook);
  }

  @Test
  void ownerOnlyAndRoleGuardsBlockOtherUsers() throws Exception {
    var s = readyDirect();
    call(other, get(path(s)), null, 404);
    call(other, post(path(s) + "/confirm"), Map.of("accountId", channel.getExternalId()), 404);
    call(null, post("/creators/portal/accounts/connect/instagram/start"), Map.of(), 401);
    for (Role role : List.of(Role.BRAND, Role.ADMIN, Role.DATA_MANAGER))
      call(account(role), post("/creators/portal/accounts/connect/instagram/start"), Map.of(), 403);
    unowned();
    verifyNoInteractions(facebook);
  }

  @Test
  void wrongCookieInvalidStateExpiryAndCallbackReplayNeverReuseCode() throws Exception {
    var s = startDirect(creator, Map.of());
    call(
        null,
        get("/integrations/instagram/callback")
            .param("state", s.state())
            .param("code", "one-time-code"),
        null,
        403);
    call(
        null,
        get("/integrations/instagram/callback")
            .cookie(s.cookie())
            .param("state", "bad")
            .param("code", "one-time-code"),
        null,
        400);
    verifyNoInteractions(instagram);
    callback(s, 200);
    callback(s, 409);
    verify(instagram, times(1)).exchange(anyString());
    var expired = startDirect(creator, Map.of());
    now = now.plusSeconds(600);
    callback(expired, 409);
    store.expire();
    assertThat(sessions.findById(s.id()).orElseThrow().getPayloadCiphertext()).isNull();
    unowned();
  }

  @Test
  void providerDenialAndRevocationClearProofWithoutGrant() throws Exception {
    var denied = startDirect(creator, Map.of());
    call(
        null,
        get("/integrations/instagram/callback")
            .cookie(denied.cookie())
            .param("state", denied.state())
            .param("error", "access_denied"),
        null,
        403);
    assertThat(sessions.findById(denied.id()).orElseThrow().getStatus()).isEqualTo("FAILED");
    verifyNoInteractions(instagram);
    var s = readyDirect();
    when(instagram.verify(any()))
        .thenThrow(new BusinessException(ErrorCode.META_AUTHORIZATION_REJECTED, "Revoked"));
    confirm(s, channel.getExternalId(), 403);
    unowned();
    assertThat(sessions.findById(s.id()).orElseThrow().getPayloadCiphertext()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"unmatched", "unstable", "foreign_selection"})
  void onlyExactStableIdsCanBeConfirmed(String reason) throws Exception {
    var s = readyDirect();
    String expected = channel.getExternalId();
    long count = creators.count();
    if (reason.equals("unmatched")) channel.setExternalId("changed" + expected);
    if (reason.equals("unstable")) channel.setIsStableId(false);
    creators.saveAndFlush(channel);
    confirm(s, reason.equals("foreign_selection") ? "111" : expected, 409);
    unowned();
    assertThat(creators.count()).isEqualTo(count);
  }

  @ParameterizedTest
  @ValueSource(strings = {"authVersion", "role", "disabled", "unverified", "permission"})
  void userAndPermissionChangesDuringHttpAreRechecked(String change) throws Exception {
    var s = readyDirect();
    when(instagram.verify(any()))
        .thenAnswer(
            i -> {
              switch (change) {
                case "authVersion" ->
                    jdbc.update(
                        "update \"user\" set auth_version=auth_version+1 where id=?",
                        creator.getId());
                case "role" ->
                    jdbc.update("update \"user\" set role='BRAND' where id=?", creator.getId());
                case "disabled" ->
                    jdbc.update(
                        "update \"user\" set status='DISABLED' where id=?", creator.getId());
                case "unverified" ->
                    jdbc.update(
                        "update \"user\" set email_verified=false where id=?", creator.getId());
                default ->
                    jdbc.update(
                        "insert into role_permission_override(id,role,permission,enabled)"
                            + " values(?,'CREATOR','CLAIM_CREATOR_PROFILE',false)",
                        UUID.randomUUID());
              }
              return directProof();
            });
    try {
      confirm(s, channel.getExternalId(), 403);
      unowned();
    } finally {
      if (change.equals("permission"))
        jdbc.update(
            "delete from role_permission_override where role='CREATOR' and"
                + " permission='CLAIM_CREATOR_PROFILE'");
    }
  }

  @Test
  void otherOwnerIsNeverTransferredAnd409AuditCommits() throws Exception {
    var s = readyDirect();
    var master =
        masters.saveAndFlush(
            CreatorMaster.builder().user(other).canonicalName("Other").status("ACTIVE").build());
    channel.setMaster(master);
    channel.setSourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED);
    channel.setAuthorizedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    creators.saveAndFlush(channel);
    confirm(s, channel.getExternalId(), 409);
    assertThat(creators.findById(channel.getId()).orElseThrow().getMaster().getId())
        .isEqualTo(master.getId());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where entity_id=? and"
                    + " action='REJECT_CREATOR_INVITATION_OR_OWNERSHIP'",
                Long.class,
                channel.getId()))
        .isEqualTo(1L);
    assertThat(masters.findByUserId(creator.getId())).isEmpty();
    verifyNoInteractions(facebook);
  }

  @ParameterizedTest
  @ValueSource(strings = {"email", "expired", "used"})
  void invitationsCannotBeBypassedOrConsumedWhenInvalid(String reason) throws Exception {
    var s = readyDirect();
    var invite =
        invitation(
            reason.equals("email") ? other : creator,
            LocalDateTime.ofInstant(now.plusSeconds(60), ZoneOffset.UTC));
    if (!reason.equals("email")) {
      // Bind the invitation before it expires/is consumed so it remains checked even when no longer
      // pending.
      sessions
          .findById(s.id())
          .ifPresent(
              row -> {
                row.setInvitation(invite);
                sessions.saveAndFlush(row);
              });
      if (reason.equals("expired"))
        invite.setExpiresAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
      else invite.setStatus("ACCEPTED");
      invitations.saveAndFlush(invite);
    }
    confirm(s, channel.getExternalId(), reason.equals("email") ? 403 : 409);
    unowned();
    assertThat(masters.findByUserId(creator.getId())).isEmpty();
  }

  @Test
  void validInvitationIsConsumedAtomicallyWithoutCreatingAnotherUser() throws Exception {
    String token = UUID.randomUUID().toString();
    var invite = invitation(creator, LocalDateTime.ofInstant(now.plusSeconds(60), ZoneOffset.UTC));
    invite.setTokenHash(
        com.influencermatch.backend.creator.service.CreatorInvitationService.hashToken(token));
    invitations.saveAndFlush(invite);
    long count = users.count();
    var s = startDirect(creator, Map.of("invitationToken", token));
    callback(s, 200);
    confirm(s, channel.getExternalId(), 200);
    assertThat(invitations.findById(invite.getId()).orElseThrow().getStatus())
        .isEqualTo("ACCEPTED");
    assertThat(users.count()).isEqualTo(count);
  }

  @Test
  void twoConfirmsHaveOneWinnerAndOneEvidenceSet() throws Exception {
    var s = readyDirect();
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    when(instagram.verify(any()))
        .thenAnswer(
            i -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              entered.countDown();
              assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
              return directProof();
            });
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Integer> request =
          () ->
              mvc.perform(
                      post(path(s) + "/confirm")
                          .header("Authorization", "Bearer " + jwt.generateToken(creator))
                          .contentType("application/json")
                          .content(
                              json.writeValueAsBytes(Map.of("accountId", channel.getExternalId()))))
                  .andReturn()
                  .getResponse()
                  .getStatus();
      var first = pool.submit(request);
      var second = pool.submit(request);
      try {
        assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    }
    assertThat(verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE")).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_provider_identity where creator_id=?",
                Long.class,
                channel.getId()))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_identity_audit where account_id=? and"
                    + " event_type='LINK_META_ACCOUNT'",
                Long.class,
                channel.getId()))
        .isEqualTo(1L);
  }

  @Test
  void auditFailureRollsBackOwnershipIdentityAndSessionCompletion() throws Exception {
    var s = readyDirect();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String fn = "ig_fail_" + suffix, tr = "ig_tr_" + suffix;
    jdbc.execute(
        "create function "
            + fn
            + "() returns trigger language plpgsql as $$ begin if new.account_id='"
            + channel.getId()
            + "'::uuid then raise exception 'Audit failed'; end if; return new; end $$");
    jdbc.execute(
        "create trigger "
            + tr
            + " before insert on creator_identity_audit for each row execute function "
            + fn
            + "()");
    try {
      confirm(s, channel.getExternalId(), 500);
      unowned();
      assertThat(masters.findByUserId(creator.getId())).isEmpty();
      assertThat(sessions.findById(s.id()).orElseThrow().getStatus()).isEqualTo("READY");
      assertThat(sessions.findById(s.id()).orElseThrow().getPayloadCiphertext()).isNotNull();
    } finally {
      jdbc.execute("drop trigger " + tr + " on creator_identity_audit");
      jdbc.execute("drop function " + fn + "()");
    }
    confirm(s, channel.getExternalId(), 200);
  }
}
