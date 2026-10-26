package com.influencermatch.backend.creator.meta;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestPropertySource(
    properties = {
      "application.meta.enabled=true",
      "application.instagram.enabled=false",
      "application.meta.app-id=123",
      "application.meta.app-secret=test-secret",
      "application.meta.login-config-id=456",
      "application.meta.graph-api-version=v24.0",
      "application.meta.redirect-uri=http://localhost:8080/api/v1/integrations/meta/callback",
      "application.meta.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
      "application.meta.cleanup-enabled=false"
    })
class MetaOAuthPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean MetaGraphClient graph;
  @MockBean Clock clock;
  @Autowired MetaOAuthSessionRepository sessions;
  @Autowired MetaSessionStore store;
  @Autowired CreatorRepository creators;
  @Autowired CreatorMasterRepository masters;
  @Autowired CreatorInvitationRepository invitations;
  @Autowired CreatorAccountVerificationRepository verifications;
  User creator, other;
  Creator channel;
  Instant now;

  record Started(UUID id, String state, jakarta.servlet.http.Cookie cookie) {}

  @BeforeEach
  void setup() {
    now = MetaGraphClientTest.NOW;
    when(clock.instant()).thenAnswer(i -> now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    creator = account(Role.CREATOR);
    other = account(Role.CREATOR);
    channel = channel("FACEBOOK");
    when(graph.exchange(anyString(), anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return proof();
            });
    when(graph.verify(anyString(), anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return proof();
            });
  }

  Creator channel(String platform) {
    return creators.saveAndFlush(
        Creator.builder()
            .platform(platform)
            .externalId(Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits()))
            .userName("same-name")
            .sourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.PUBLIC)
            .isStableId(true)
            .build());
  }

  MetaDtos.Proof proof() {
    return new MetaDtos.Proof(
        "meta-user-99",
        "private-user-token",
        now.plusSeconds(3600),
        Set.of("pages_show_list", "pages_read_engagement", "instagram_basic"),
        List.of(
            new MetaDtos.Asset(
                channel.getExternalId(),
                channel.getPlatform().equals("INSTAGRAM") ? "111" : channel.getExternalId(),
                "Meta Account",
                "same-name")));
  }

  Started start(User actor, Map<String, Object> body) throws Exception {
    var response =
        mvc.perform(
                post("/creators/portal/accounts/connect/meta/start")
                    .header("Authorization", "Bearer " + jwt.generateToken(actor))
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    var data = json.readTree(response.getContentAsString()).path("data");
    UUID id = UUID.fromString(data.path("sessionId").asText());
    URI url = URI.create(data.path("authorizationUrl").asText());
    String state =
        Arrays.stream(url.getRawQuery().split("&"))
            .filter(p -> p.startsWith("state="))
            .map(p -> URLDecoder.decode(p.substring(6), StandardCharsets.UTF_8))
            .findFirst()
            .orElseThrow();
    assertThat(url.getHost()).isEqualTo("www.facebook.com");
    assertThat(state).hasSize(64).doesNotContain(actor.getId().toString());
    assertThat(sessions.findById(id).orElseThrow().getStateHash()).isNotEqualTo(state);
    var header = response.getHeader("Set-Cookie");
    assertThat(header).contains("HttpOnly", "SameSite=Lax");
    var parsed = HttpCookie.parse(header).getFirst();
    var cookie = new jakarta.servlet.http.Cookie(parsed.getName(), parsed.getValue());
    assertThat(sessions.findById(id).orElseThrow().getBrowserBindingHash())
        .isNotEqualTo(cookie.getValue());
    return new Started(id, state, cookie);
  }

  Started ready(User actor) throws Exception {
    var started = start(actor, Map.of("platform", channel.getPlatform()));
    var response = callback(started, 200);
    assertThat(response.path("data").path("status").asText()).isEqualTo("READY");
    assertThat(response.toString())
        .doesNotContain("private-user-token", "same-name", "accounts", "scope");
    return started;
  }

  com.fasterxml.jackson.databind.JsonNode callback(Started s, int expected) throws Exception {
    return call(
        null,
        get("/integrations/meta/callback")
            .cookie(s.cookie())
            .param("state", s.state())
            .param("code", "single-code"),
        null,
        expected);
  }

  com.fasterxml.jackson.databind.JsonNode confirm(User actor, Started s, String id, int expected)
      throws Exception {
    return call(
        actor,
        post("/creators/portal/accounts/connect/meta/sessions/" + s.id() + "/confirm"),
        Map.of("accountId", id),
        expected);
  }

  String view(Started s) {
    return "/creators/portal/accounts/connect/meta/sessions/" + s.id();
  }

  void assertUnowned() {
    assertThat(creators.findById(channel.getId()).orElseThrow().getMaster()).isNull();
    assertThat(verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"FACEBOOK", "INSTAGRAM"})
  void actualProofConfirmsOnlyExistingIdAndPurgesSensitivePayload(String platform)
      throws Exception {
    if (platform.equals("INSTAGRAM")) channel = channel(platform);
    var s = ready(creator);
    var stored = sessions.findById(s.id()).orElseThrow();
    assertThat(stored.getPayloadCiphertext()).doesNotContain("private-user-token", "same-name");
    var choices = call(creator, get(view(s)), null, 200).path("data");
    assertThat(choices.path("accounts").get(0).path("creatorId").asText())
        .isEqualTo(channel.getId().toString());
    assertThat(choices.toString())
        .doesNotContain("private-user-token", "tokenExpiresAt", "subject");
    assertUnowned();
    long before = creators.count();
    var result = confirm(creator, s, channel.getExternalId(), 200).path("data");
    assertThat(result.path("status").asText()).isEqualTo("AUTHORIZED");
    assertThat(creators.count()).isEqualTo(before);
    assertThat(masters.findByUserId(creator.getId())).isPresent();
    assertThat(sessions.findById(s.id()).orElseThrow().getPayloadCiphertext()).isNull();
    var verification = verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE");
    assertThat(verification).hasSize(1);
    assertThat(verification.getFirst().getVerificationMethod()).isEqualTo("META_OAUTH");
    assertThat(verification.getFirst().getAccessTokenEncrypted()).isNull();
    assertThat(verification.getFirst().getRefreshTokenEncrypted()).isNull();
    var row =
        jdbc.queryForObject(
            "select raw_profile_data::text from creator_provider_identity where creator_id=?",
            String.class,
            channel.getId());
    assertThat(row).doesNotContain("private-user-token", "meta-user-99");
    assertThat(confirm(creator, s, channel.getExternalId(), 409).path("code").asText())
        .isEqualTo("META_OAUTH_ALREADY_PROCESSED");
    verify(graph, times(1)).verify(anyString(), eq(platform));
  }

  @Test
  void instagramCannotConfirmItsFacebookPageIdEvenIfThatIdExistsInTheDatabase() throws Exception {
    channel = channel("INSTAGRAM");
    var misleading = channel("INSTAGRAM");
    misleading.setExternalId("111");
    creators.saveAndFlush(misleading);
    var s = ready(creator);
    long before = creators.count();
    var response = confirm(creator, s, "111", 409);
    assertThat(response.path("code").asText()).isEqualTo("META_ACCOUNT_NOT_MATCHED");
    verify(graph, never()).verify(anyString(), anyString());
    assertUnowned();
    assertThat(creators.findById(misleading.getId()).orElseThrow().getMaster()).isNull();
    assertThat(creators.count()).isEqualTo(before);
    assertThat(masters.findByUserId(creator.getId())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_provider_identity where creator_id in (?,?)",
                Long.class,
                channel.getId(),
                misleading.getId()))
        .isZero();
    assertThat(sessions.findById(s.id()).orElseThrow().getStatus()).isEqualTo("READY");
  }

  @Test
  void instagramReusesFacebookMasterAndPreservesExistingFacebookEvidence() throws Exception {
    var facebook = channel;
    var facebookSession = ready(creator);
    UUID master =
        UUID.fromString(
            confirm(creator, facebookSession, facebook.getExternalId(), 200)
                .path("data")
                .path("masterId")
                .asText());
    String facebookBefore =
        jdbc.queryForObject(
            "select to_jsonb(c)::text from creator c where id=?", String.class, facebook.getId());
    String evidenceBefore =
        jdbc.queryForObject(
            "select to_jsonb(v)::text from creator_account_verification v where creator_id=?",
            String.class,
            facebook.getId());
    String identityBefore =
        jdbc.queryForObject(
            "select to_jsonb(i)::text from creator_provider_identity i where creator_id=?",
            String.class,
            facebook.getId());
    channel = channel("INSTAGRAM");
    var baseProof = proof();
    var granted = new HashSet<>(baseProof.scopes());
    granted.add("business_management");
    var instagramProof =
        new MetaDtos.Proof(
            baseProof.subject(),
            baseProof.token(),
            baseProof.tokenExpiresAt(),
            Set.copyOf(granted),
            List.of(
                new MetaDtos.Asset(
                    channel.getExternalId(),
                    facebook.getExternalId(),
                    "Instagram Creator",
                    "ig-test")));
    when(graph.exchange(anyString(), eq("INSTAGRAM")))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return instagramProof;
            });
    when(graph.verify(anyString(), eq("INSTAGRAM")))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return instagramProof;
            });
    var s = ready(creator);
    var result = confirm(creator, s, channel.getExternalId(), 200).path("data");
    assertThat(result.path("masterId").asText()).isEqualTo(master.toString());
    assertThat(creators.findById(channel.getId()).orElseThrow().getMaster().getId())
        .isEqualTo(master);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_master where user_id=?", Long.class, creator.getId()))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "select to_jsonb(c)::text from creator c where id=?",
                String.class,
                facebook.getId()))
        .isEqualTo(facebookBefore);
    assertThat(
            jdbc.queryForObject(
                "select to_jsonb(v)::text from creator_account_verification v where creator_id=?",
                String.class,
                facebook.getId()))
        .isEqualTo(evidenceBefore);
    assertThat(
            jdbc.queryForObject(
                "select to_jsonb(i)::text from creator_provider_identity i where creator_id=?",
                String.class,
                facebook.getId()))
        .isEqualTo(identityBefore);
    var verification = verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE");
    assertThat(verification).hasSize(1);
    assertThat(verification.getFirst().getGrantedScopes())
        .contains("business_management", "instagram_basic");
    assertThat(verification.getFirst().getAccessTokenEncrypted()).isNull();
    assertThat(verification.getFirst().getRefreshTokenEncrypted()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select raw_profile_data->>'pageId' from creator_provider_identity where"
                    + " creator_id=? and provider='INSTAGRAM'",
                String.class,
                channel.getId()))
        .isEqualTo(facebook.getExternalId());
    assertThat(sessions.findById(s.id()).orElseThrow().getPayloadCiphertext()).isNull();
    assertThat(confirm(creator, s, channel.getExternalId(), 409).path("code").asText())
        .isEqualTo("META_OAUTH_ALREADY_PROCESSED");
    assertThat(verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE")).hasSize(1);
  }

  @Test
  void onlySessionOwnerMayViewAndConfirmAndNonCreatorsCannotStart() throws Exception {
    var s = ready(creator);
    call(other, get(view(s)), null, 404);
    confirm(other, s, channel.getExternalId(), 404);
    call(null, get(view(s)), null, 401);
    confirm(null, s, channel.getExternalId(), 401);
    for (Role role : List.of(Role.BRAND, Role.ADMIN, Role.DATA_MANAGER))
      call(
          account(role),
          post("/creators/portal/accounts/connect/meta/start"),
          Map.of("platform", "FACEBOOK"),
          403);
    call(
        creator,
        post("/creators/portal/accounts/connect/meta/start"),
        Map.of("platform", "TIKTOK"),
        400);
    assertUnowned();
  }

  @Test
  void stateReplayAndExpiredSessionsNeverReuseAuthorizationCode() throws Exception {
    var s = ready(creator);
    callback(s, 409);
    verify(graph, times(1)).exchange(anyString(), anyString());
    call(
        null,
        get("/integrations/meta/callback").param("state", "bad").param("code", "code"),
        null,
        400);
    now = now.plusSeconds(600);
    assertThat(confirm(creator, s, channel.getExternalId(), 409).path("code").asText())
        .isEqualTo("META_OAUTH_SESSION_EXPIRED");
    assertThat(call(creator, get(view(s)), null, 200).path("data").path("status").asText())
        .isEqualTo("EXPIRED");
    store.expire();
    assertThat(sessions.findById(s.id()).orElseThrow().getPayloadCiphertext()).isNull();
    assertUnowned();
  }

  @Test
  void declinedOrFailedCallbackConsumesStateAndKeepsOwnershipUnchanged() throws Exception {
    var denied = start(creator, Map.of("platform", "FACEBOOK"));
    call(
        null,
        get("/integrations/meta/callback")
            .cookie(denied.cookie())
            .param("state", denied.state())
            .param("error", "access_denied"),
        null,
        403);
    assertThat(sessions.findById(denied.id()).orElseThrow().getStatus()).isEqualTo("FAILED");
    callback(denied, 409);
    var failed = start(creator, Map.of("platform", "FACEBOOK"));
    when(graph.exchange(anyString(), anyString()))
        .thenThrow(new BusinessException(ErrorCode.META_PROVIDER_ERROR, "Provider unavailable"));
    callback(failed, 502);
    assertThat(sessions.findById(failed.id()).orElseThrow().getPayloadCiphertext()).isNull();
    callback(failed, 409);
    assertUnowned();
  }

  @ParameterizedTest
  @ValueSource(strings = {"unmatched", "unstable", "not-listed", "wrong-platform"})
  void neverCreatesChannelsOrMatchesByUsername(String reason) throws Exception {
    var s = ready(creator);
    long count = creators.count();
    switch (reason) {
      case "unmatched" -> {
        channel.setExternalId("different-public-id");
        creators.saveAndFlush(channel);
      }
      case "unstable" -> {
        channel.setIsStableId(false);
        creators.saveAndFlush(channel);
      }
      case "wrong-platform" -> {
        channel.setPlatform("INSTAGRAM");
        creators.saveAndFlush(channel);
      }
    }
    var original = proof().assets().getFirst().id();
    // For changed-ID case the original ID remains in the encrypted snapshot, not the current
    // profile.
    if (reason.equals("unmatched")) {
      var choices = call(creator, get(view(s)), null, 200).path("data").path("accounts");
      original = choices.get(0).path("accountId").asText();
      when(graph.verify(anyString(), anyString()))
          .thenReturn(
              new MetaDtos.Proof(
                  "meta-user-99",
                  "private-user-token",
                  now.plusSeconds(3600),
                  proof().scopes(),
                  List.of(new MetaDtos.Asset(original, original, "Meta", "same-name"))));
    }
    confirm(creator, s, reason.equals("not-listed") ? "not-granted-id" : original, 409);
    assertThat(creators.count()).isEqualTo(count);
    assertUnowned();
    assertThat(masters.findByUserId(creator.getId())).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"authVersion", "disabled", "unverified", "permission", "role"})
  void stateChangesDuringProviderVerificationAreRecheckedBeforeCommit(String change)
      throws Exception {
    var s = ready(creator);
    when(graph.verify(anyString(), anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              switch (change) {
                case "authVersion" ->
                    jdbc.update(
                        "update \"user\" set auth_version=auth_version+1 where id=?",
                        creator.getId());
                case "disabled" ->
                    jdbc.update(
                        "update \"user\" set status='DISABLED' where id=?", creator.getId());
                case "unverified" ->
                    jdbc.update(
                        "update \"user\" set email_verified=false where id=?", creator.getId());
                case "role" ->
                    jdbc.update("update \"user\" set role='BRAND' where id=?", creator.getId());
                case "permission" ->
                    jdbc.update(
                        "insert into role_permission_override(id,role,permission,enabled)"
                            + " values(?,'CREATOR','CLAIM_CREATOR_PROFILE',false)",
                        UUID.randomUUID());
              }
              return proof();
            });
    try {
      confirm(creator, s, channel.getExternalId(), 403);
      assertUnowned();
    } finally {
      if (change.equals("permission"))
        jdbc.update(
            "delete from role_permission_override where role='CREATOR' and"
                + " permission='CLAIM_CREATOR_PROFILE'");
    }
  }

  CreatorInvitation invite(String email, LocalDateTime expiry) {
    var brand = account(Role.BRAND);
    return invitations.saveAndFlush(
        CreatorInvitation.builder()
            .targetCreator(channel)
            .invitedBy(brand)
            .invitedEmail(email)
            .tokenHash(UUID.randomUUID().toString().replace("-", "").repeat(2))
            .status("PENDING")
            .expiresAt(expiry)
            .build());
  }

  @Test
  void generalConnectCannotBypassAnInvitationToAnotherEmail() throws Exception {
    var s = ready(creator);
    var invitation =
        invite(other.getEmail(), LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC));
    confirm(creator, s, channel.getExternalId(), 403);
    assertUnowned();
    assertThat(invitations.findById(invitation.getId()).orElseThrow().getStatus())
        .isEqualTo("PENDING");
  }

  @Test
  void explicitInvitationIsBoundToTargetEmailAndConsumedWithOwnership() throws Exception {
    String token = UUID.randomUUID().toString();
    var invitation =
        invite(creator.getEmail(), LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC));
    invitation.setTokenHash(
        com.influencermatch.backend.creator.service.CreatorInvitationService.hashToken(token));
    invitations.saveAndFlush(invitation);
    call(
        other,
        post("/creators/portal/accounts/connect/meta/start"),
        Map.of("platform", "FACEBOOK", "invitationToken", token),
        403);
    var s = start(creator, Map.of("platform", "FACEBOOK", "invitationToken", token));
    callback(s, 200);
    confirm(creator, s, channel.getExternalId(), 200);
    assertThat(invitations.findById(invitation.getId()).orElseThrow().getStatus())
        .isEqualTo("ACCEPTED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"expired", "used"})
  void explicitInvitationIsRecheckedAfterOAuth(String change) throws Exception {
    String token = UUID.randomUUID().toString();
    var invitation =
        invite(creator.getEmail(), LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC));
    invitation.setTokenHash(
        com.influencermatch.backend.creator.service.CreatorInvitationService.hashToken(token));
    invitations.saveAndFlush(invitation);
    var s = start(creator, Map.of("platform", "FACEBOOK", "invitationToken", token));
    callback(s, 200);
    if (change.equals("used")) invitation.setStatus("ACCEPTED");
    else invitation.setExpiresAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    invitations.saveAndFlush(invitation);
    confirm(creator, s, channel.getExternalId(), 409);
    assertUnowned();
  }

  void setOwner(User user) {
    var master =
        masters.saveAndFlush(
            CreatorMaster.builder().user(user).canonicalName("Owner").status("ACTIVE").build());
    channel.setMaster(master);
    channel.setSourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED);
    channel.setAuthorizedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    creators.saveAndFlush(channel);
  }

  @ParameterizedTest
  @ValueSource(strings = {"FACEBOOK", "INSTAGRAM"})
  void ownershipAppearingDuringProviderCallReturns409WithDurableAudit(String platform)
      throws Exception {
    if (platform.equals("INSTAGRAM")) channel = channel(platform);
    var s = ready(creator);
    when(graph.verify(anyString(), anyString()))
        .thenAnswer(
            i -> {
              setOwner(other);
              return proof();
            });
    confirm(creator, s, channel.getExternalId(), 409);
    assertThat(
            jdbc.queryForObject(
                "select m.user_id from creator c join creator_master m on m.id=c.master_id where"
                    + " c.id=?",
                UUID.class,
                channel.getId()))
        .isEqualTo(other.getId());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where entity_id=? and"
                    + " action='REJECT_CREATOR_INVITATION_OR_OWNERSHIP'",
                Long.class,
                channel.getId()))
        .isEqualTo(1);
    assertThat(masters.findByUserId(creator.getId())).isEmpty();
    assertThat(sessions.findById(s.id()).orElseThrow().getStatus()).isEqualTo("READY");
  }

  @Test
  void concurrentConfirmOnOneSessionHasOneWinnerAndNoDuplicateEvidence() throws Exception {
    var s = ready(creator);
    var ready = new CountDownLatch(2);
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Integer> request =
          () -> {
            ready.countDown();
            assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
            return mvc.perform(
                    post(view(s) + "/confirm")
                        .header("Authorization", "Bearer " + jwt.generateToken(creator))
                        .contentType("application/json")
                        .content(
                            json.writeValueAsBytes(Map.of("accountId", channel.getExternalId()))))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var a = pool.submit(request);
      var b = pool.submit(request);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      gate.countDown();
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    }
    assertThat(verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE")).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_identity_audit where account_id=? and"
                    + " event_type='LINK_META_ACCOUNT'",
                Long.class,
                channel.getId()))
        .isEqualTo(1);
  }

  @Test
  void secondSessionForSameOwnerUpdatesEvidenceInsteadOfCreatingDuplicateRows() throws Exception {
    var first = ready(creator);
    confirm(creator, first, channel.getExternalId(), 200);
    var second = ready(creator);
    confirm(creator, second, channel.getExternalId(), 200);
    assertThat(verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE")).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_provider_identity where creator_id=?",
                Long.class,
                channel.getId()))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"FACEBOOK", "INSTAGRAM"})
  void auditFailureRollsBackMasterOwnershipEvidenceAndSessionConsumption(String platform)
      throws Exception {
    if (platform.equals("INSTAGRAM")) channel = channel(platform);
    var s = ready(creator);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String fn = "meta_fail_" + suffix, tr = "meta_fail_tr_" + suffix;
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
      confirm(creator, s, channel.getExternalId(), 500);
      assertUnowned();
      assertThat(masters.findByUserId(creator.getId())).isEmpty();
      assertThat(sessions.findById(s.id()).orElseThrow().getStatus()).isEqualTo("READY");
      assertThat(sessions.findById(s.id()).orElseThrow().getPayloadCiphertext()).isNotNull();
    } finally {
      jdbc.execute("drop trigger " + tr + " on creator_identity_audit");
      jdbc.execute("drop function " + fn + "()");
    }
    confirm(creator, s, channel.getExternalId(), 200);
  }

  @ParameterizedTest
  @ValueSource(strings = {"facebook", "instagram"})
  void oldMockRoutesCannotVerifyMetaEvenWithMockClientEnabled(String platform) throws Exception {
    call(creator, post("/creators/portal/accounts/connect/" + platform + "/start"), Map.of(), 503);
    call(
        creator,
        post("/creators/claim/oauth/" + platform + "/callback"),
        Map.of("token", "fake", "code", "sim:any:any"),
        503);
    var master =
        masters.saveAndFlush(
            CreatorMaster.builder()
                .user(creator)
                .canonicalName("Creator")
                .status("ACTIVE")
                .build());
    call(
        creator,
        post("/creators/portal/accounts/connect/" + platform + "/callback"),
        Map.of("code", "sim:any:any"),
        503);
    assertThat(master.getId()).isNotNull();
    assertUnowned();
    verifyNoInteractions(graph);
  }

  @Test
  void callbackRequiresTheOriginalBrowserAndCannotUseAForwardedAuthorizationUrl() throws Exception {
    var s = start(creator, Map.of("platform", "FACEBOOK"));
    call(
        null,
        get("/integrations/meta/callback").param("state", s.state()).param("code", "code"),
        null,
        403);
    call(
        null,
        get("/integrations/meta/callback")
            .cookie(new jakarta.servlet.http.Cookie(s.cookie().getName(), "0".repeat(64)))
            .param("state", s.state())
            .param("code", "code"),
        null,
        403);
    assertThat(sessions.findById(s.id()).orElseThrow().getStatus()).isEqualTo("NEW");
    verifyNoInteractions(graph);
    callback(s, 200);
  }

  @Test
  void simultaneousCallbacksExchangeCodeOnceAndDoNotHoldLocksDuringHttp() throws Exception {
    var s = start(creator, Map.of("platform", "FACEBOOK"));
    var fetching = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(graph.exchange(anyString(), anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              fetching.countDown();
              assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
              return proof();
            });
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> callback(s, 200));
      assertThat(fetching.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        callback(s, 409);
      } finally {
        release.countDown();
      }
      a.get(10, TimeUnit.SECONDS);
    }
    verify(graph, times(1)).exchange(anyString(), anyString());
    assertUnowned();
  }

  @Test
  void twoDifferentCreatorsCompetingForOneChannelHaveOneOwner() throws Exception {
    var a = ready(creator);
    var b = ready(other);
    var ready = new CountDownLatch(2);
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<Future<Integer>>();
      int index = 0;
      for (User user : List.of(creator, other)) {
        var session = index++ == 0 ? a : b;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
                  return mvc.perform(
                          post(view(session) + "/confirm")
                              .header("Authorization", "Bearer " + jwt.generateToken(user))
                              .contentType("application/json")
                              .content(
                                  json.writeValueAsBytes(
                                      Map.of("accountId", channel.getExternalId()))))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      gate.countDown();
      assertThat(
              List.of(
                  futures.get(0).get(20, TimeUnit.SECONDS),
                  futures.get(1).get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    }
    assertThat(verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE")).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_master where user_id in (?,?)",
                Long.class,
                creator.getId(),
                other.getId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where entity_id=? and"
                    + " action='REJECT_CREATOR_INVITATION_OR_OWNERSHIP'",
                Long.class,
                channel.getId()))
        .isEqualTo(1);
  }

  @Test
  void twoDifferentChannelsConfirmedConcurrentlyReuseOneMaster() throws Exception {
    var second = channel("FACEBOOK");
    var all =
        new MetaDtos.Proof(
            "meta-user-99",
            "private-user-token",
            now.plusSeconds(3600),
            proof().scopes(),
            List.of(
                proof().assets().getFirst(),
                new MetaDtos.Asset(
                    second.getExternalId(), second.getExternalId(), "Second", "second")));
    when(graph.exchange(anyString(), anyString())).thenReturn(all);
    when(graph.verify(anyString(), anyString())).thenReturn(all);
    var a = ready(creator);
    var b = ready(creator);
    var gate = new CountDownLatch(1);
    var ready = new CountDownLatch(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<Future<Integer>>();
      int index = 0;
      for (var account : List.of(channel, second)) {
        var s = index++ == 0 ? a : b;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
                  return mvc.perform(
                          post(view(s) + "/confirm")
                              .header("Authorization", "Bearer " + jwt.generateToken(creator))
                              .contentType("application/json")
                              .content(
                                  json.writeValueAsBytes(
                                      Map.of("accountId", account.getExternalId()))))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      gate.countDown();
      assertThat(
              List.of(
                  futures.get(0).get(20, TimeUnit.SECONDS),
                  futures.get(1).get(20, TimeUnit.SECONDS)))
          .containsExactly(200, 200);
    }
    var master = masters.findByUserId(creator.getId()).orElseThrow();
    for (var c : List.of(channel, second))
      assertThat(
              jdbc.queryForObject(
                  "select master_id from creator where id=?", UUID.class, c.getId()))
          .isEqualTo(master.getId());
  }

  @Test
  void providerRevocationPurgesProofAndShortTokenLifeBoundsSessionExpiry() throws Exception {
    var shortProof =
        new MetaDtos.Proof(
            proof().subject(),
            proof().token(),
            now.plusSeconds(60),
            proof().scopes(),
            proof().assets());
    when(graph.exchange(anyString(), anyString())).thenReturn(shortProof);
    var s = ready(creator);
    assertThat(sessions.findById(s.id()).orElseThrow().getExpiresAt())
        .isEqualTo(now.plusSeconds(60));
    when(graph.verify(anyString(), anyString()))
        .thenThrow(new BusinessException(ErrorCode.META_AUTHORIZATION_REJECTED, "Access revoked"));
    confirm(creator, s, channel.getExternalId(), 403);
    assertThat(sessions.findById(s.id()).orElseThrow().getPayloadCiphertext()).isNull();
    assertUnowned();
  }

  @Test
  void v16UpgradePreservesExistingAccountOwnershipAndDoesNotInventSessions() {
    String schema = "meta_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try {
      var old =
          org.flywaydb.core.Flyway.configure()
              .dataSource(jdbc.getDataSource())
              .schemas(schema)
              .defaultSchema(schema)
              .target("16")
              .load();
      old.migrate();
      UUID u = UUID.randomUUID(), c = UUID.randomUUID(), m = UUID.randomUUID();
      jdbc.update(
          "insert into "
              + schema
              + ".\"user\"(id,email,full_name,role,status,email_verified,created_at,updated_at)"
              + " values(?,'keep@example.test','Keep','CREATOR','ACTIVE',true,now(),now())",
          u);
      jdbc.update(
          "insert into "
              + schema
              + ".creator_master(id,user_id,canonical_name,status,created_at,updated_at)"
              + " values(?,?,'Keep','ACTIVE',now(),now())",
          m,
          u);
      jdbc.update(
          "insert into "
              + schema
              + ".creator(id,platform,external_id,user_name,master_id,source_type,authorized_at,created_at,updated_at)"
              + " values(?,'FACEBOOK','111','Keep',?,'AUTHORIZED',now(),now(),now())",
          c,
          m);
      var before =
          jdbc.queryForObject(
              "select to_jsonb(c)::text from " + schema + ".creator c where id=?", String.class, c);
      assertThat(
              org.flywaydb.core.Flyway.configure()
                  .dataSource(jdbc.getDataSource())
                  .schemas(schema)
                  .defaultSchema(schema)
                  .target("17")
                  .load()
                  .migrate()
                  .migrationsExecuted)
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select to_jsonb(c)::text from " + schema + ".creator c where id=?",
                  String.class,
                  c))
          .isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + schema + ".meta_oauth_session", Long.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + schema + ".\"user\" where id=? and email_verified=true",
                  Long.class,
                  u))
          .isEqualTo(1);
    } finally {
      jdbc.execute("drop schema if exists " + schema + " cascade");
    }
  }
}
