package com.influencermatch.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.ExternalIdentityRepository;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.support.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class GoogleBrandLoginPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean GoogleIdTokenVerifier verifier;
  @Autowired ExternalIdentityRepository identities;
  @SpyBean AuthSessionIssuer issuer;
  @SpyBean com.influencermatch.backend.audit.service.AuditService audit;
  final String client = "test.apps.googleusercontent.com";

  @BeforeEach
  void configured() {
    when(verifier.requireConfigured()).thenReturn(client);
  }

  record Attempt(UUID id, String nonce, Cookie cookie) {}

  Attempt start() throws Exception {
    return started(
        mvc.perform(post("/auth/google/start").with(TestAuthHttp.csrf()))
            .andExpect(status().isOk())
            .andReturn());
  }

  Attempt started(MvcResult result) throws Exception {
    var data = json.readTree(result.getResponse().getContentAsString()).path("data");
    UUID id = UUID.fromString(data.path("sessionId").asText());
    var cookie = result.getResponse().getCookie("google_login_" + id);
    assertThat(cookie).isNotNull();
    assertThat(cookie.isHttpOnly()).isTrue();
    return new Attempt(id, data.path("nonce").asText(), cookie);
  }

  String proof(Attempt a, String email, String subject) {
    String raw = UUID.randomUUID().toString();
    when(verifier.verify(raw))
        .thenReturn(
            org.springframework.security.oauth2.jwt.Jwt.withTokenValue("not-real-token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", "Google Brand")
                .claim("nonce", a.nonce)
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
    return raw;
  }

  MvcResult login(Attempt a, String raw, int code) throws Exception {
    return mvc.perform(
            post("/auth/google/login")
                .with(TestAuthHttp.csrf())
                .cookie(a.cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("sessionId", a.id, "credential", raw))))
        .andExpect(status().is(code))
        .andReturn();
  }

  MvcResult complete(Attempt a, int code) throws Exception {
    return mvc.perform(
            post("/auth/google/registration/complete")
                .with(TestAuthHttp.csrf())
                .cookie(a.cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "sessionId",
                            a.id,
                            "fullName",
                            "Brand Owner",
                            "businessName",
                            "Google Brand",
                            "phoneNumber",
                            "0912345678",
                            "policyAcceptance",
                            TestAuthHttp.acceptance()))))
        .andExpect(status().is(code))
        .andReturn();
  }

  void identity(User user, String subject) {
    identities.saveAndFlush(
        ExternalIdentity.builder()
            .user(user)
            .provider(ExternalIdentityProvider.GOOGLE)
            .providerSubject(subject)
            .providerEmail(user.getEmail())
            .emailVerified(true)
            .build());
  }

  String email() {
    return "google-" + UUID.randomUUID() + "@gmail.com";
  }

  @Test
  void createsVerifiedBrandOnlyAfterFormAndReplayCannotIssueAnotherSession() throws Exception {
    Attempt a = start();
    String email = email(), subject = UUID.randomUUID().toString(), raw = proof(a, email, subject);
    var pending = json.readTree(login(a, raw, 200).getResponse().getContentAsString()).path("data");
    assertThat(pending.path("status").asText()).isEqualTo("REGISTRATION_REQUIRED");
    assertThat(pending.has("accessToken")).isFalse();
    assertThat(users.findByEmail(email)).isEmpty();
    var done = complete(a, 200).getResponse();
    var data = json.readTree(done.getContentAsString()).path("data");
    assertThat(data.path("status").asText()).isEqualTo("AUTHENTICATED");
    assertThat(data.path("accessToken").asText()).isNotBlank();
    assertThat(data.has("refreshToken")).isFalse();
    assertThat(done.getCookie("IM_REFRESH")).isNotNull();
    var user = users.findByEmail(email).orElseThrow();
    assertThat(user.getRole()).isEqualTo(Role.BRAND);
    assertThat(user.isEmailVerified()).isTrue();
    assertThat(user.getPasswordHash()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from brand_profile where user_id=?", Integer.class, user.getId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_policy_acceptance where user_id=?",
                Integer.class,
                user.getId()))
        .isEqualTo(2);
    login(a, raw, 409);
    complete(a, 409);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_token where user_id=?", Integer.class, user.getId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select google_email from google_login_session where id=?", String.class, a.id))
        .isNull();
  }

  @Test
  void knownGoogleSubjectReusesBrandAndSupportsRefreshAndMe() throws Exception {
    var user = account(Role.BRAND);
    String subject = UUID.randomUUID().toString();
    identity(user, subject);
    var a = start();
    var r = login(a, proof(a, user.getEmail(), subject), 200).getResponse();
    var data = json.readTree(r.getContentAsString()).path("data");
    assertThat(data.path("user").path("id").asText()).isEqualTo(user.getId().toString());
    assertThat(data.has("refreshToken")).isFalse();
    var me = callToken(data.path("accessToken").asText(), get("/auth/me"), null, 200);
    assertThat(me.path("data").path("permissions"))
        .isEqualTo(data.path("user").path("permissions"));
    mvc.perform(post("/auth/refresh").with(TestAuthHttp.csrf()).cookie(r.getCookie("IM_REFRESH")))
        .andExpect(status().isOk());
  }

  @Test
  void sameEmailNeverAutomaticallyLinksPasswordAccount() throws Exception {
    var u = account(Role.BRAND);
    var a = start();
    login(a, proof(a, u.getEmail(), UUID.randomUUID().toString()), 409);
    assertThat(identities.findByUserIdAndProvider(u.getId(), ExternalIdentityProvider.GOOGLE))
        .isEmpty();
  }

  @Test
  void pendingOtpCannotBeActivatedByGoogle() throws Exception {
    var u = account(Role.BRAND);
    u.setEmailVerified(false);
    users.saveAndFlush(u);
    var a = start();
    login(a, proof(a, u.getEmail(), UUID.randomUUID().toString()), 403);
    assertThat(users.findById(u.getId()).orElseThrow().isEmailVerified()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"CREATOR", "ADMIN", "DATA_MANAGER"})
  void onlyBrandCanUseKnownIdentity(Role role) throws Exception {
    var u = account(role);
    String sub = UUID.randomUUID().toString();
    identity(u, sub);
    var a = start();
    login(a, proof(a, u.getEmail(), sub), 403);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_token where user_id=?", Integer.class, u.getId()))
        .isZero();
  }

  @ParameterizedTest
  @EnumSource(
      value = UserStatus.class,
      names = {"LOCKED", "DISABLED"})
  void blockedBrandCannotReceiveTokens(UserStatus state) throws Exception {
    var u = account(Role.BRAND);
    u.setStatus(state);
    users.saveAndFlush(u);
    String sub = UUID.randomUUID().toString();
    identity(u, sub);
    var a = start();
    login(a, proof(a, u.getEmail(), sub), 401);
  }

  @Test
  void wrongBrowserNonceAndExpiredSessionCannotCreateUser() throws Exception {
    var a = start();
    String email = email(), raw = proof(a, email, UUID.randomUUID().toString());
    login(new Attempt(a.id, a.nonce, new Cookie(a.cookie.getName(), "wrong")), raw, 401);
    var other = start();
    login(a, proof(other, email, UUID.randomUUID().toString()), 401);
    jdbc.update(
        "update google_login_session set expires_at=now()-interval '1 second' where id=?", a.id);
    login(a, raw, 401);
    assertThat(users.findByEmail(email)).isEmpty();
  }

  @Test
  void invalidProviderCredentialCannotCreateAccount() throws Exception {
    var a = start();
    when(verifier.verify("bad"))
        .thenThrow(
            new com.influencermatch.backend.exception.BusinessException(
                com.influencermatch.backend.exception.ErrorCode.UNAUTHENTICATED,
                "Invalid credential"));
    login(a, "bad", 401);
    assertThat(
            jdbc.queryForObject(
                "select consumed_at is null from google_login_session where id=?",
                Boolean.class,
                a.id))
        .isTrue();
  }

  @Test
  void disabledConfigurationReturns503WithoutCreatingSession() throws Exception {
    when(verifier.requireConfigured())
        .thenThrow(
            new com.influencermatch.backend.exception.BusinessException(
                com.influencermatch.backend.exception.ErrorCode.GOOGLE_LOGIN_UNAVAILABLE,
                "Disabled"));
    mvc.perform(post("/auth/google/start").with(TestAuthHttp.csrf()))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void issuingSessionFailureRollsBackUserProfileIdentityConsentAndConsumption() throws Exception {
    var a = start();
    String email = email();
    login(a, proof(a, email, UUID.randomUUID().toString()), 200);
    doThrow(
            new com.influencermatch.backend.exception.BusinessException(
                com.influencermatch.backend.exception.ErrorCode.UNAUTHENTICATED, "Failure"))
        .when(issuer)
        .issue(any(User.class), eq("GOOGLE"));
    complete(a, 401);
    assertThat(users.findByEmail(email)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select status from google_login_session where id=?", String.class, a.id))
        .isEqualTo("REGISTRATION_REQUIRED");
  }

  @Test
  void simultaneousLoginConsumesProofOnceAndSimultaneousCompleteCreatesOneAccount()
      throws Exception {
    var a = start();
    String email = email(), raw = proof(a, email, UUID.randomUUID().toString());
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> task =
          () -> {
            gate.await();
            return mvc.perform(
                    post("/auth/google/login")
                        .with(TestAuthHttp.csrf())
                        .cookie(a.cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsBytes(Map.of("sessionId", a.id, "credential", raw))))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var x = pool.submit(task);
      var y = pool.submit(task);
      gate.countDown();
      assertThat(List.of(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      var gate2 = new CountDownLatch(1);
      Callable<Integer> finish =
          () -> {
            gate2.await();
            return mvc.perform(
                    post("/auth/google/registration/complete")
                        .with(TestAuthHttp.csrf())
                        .cookie(a.cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsBytes(
                                Map.of(
                                    "sessionId",
                                    a.id,
                                    "fullName",
                                    "Owner",
                                    "businessName",
                                    "Brand",
                                    "phoneNumber",
                                    "0912345678",
                                    "policyAcceptance",
                                    TestAuthHttp.acceptance()))))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var p = pool.submit(finish);
      var q = pool.submit(finish);
      gate2.countDown();
      assertThat(List.of(p.get(20, TimeUnit.SECONDS), q.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      var u = users.findByEmail(email).orElseThrow();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from refresh_token where user_id=?", Integer.class, u.getId()))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  Attempt linkStart(User u, String access, int code) throws Exception {
    var r =
        mvc.perform(
                post("/auth/google/link/start")
                    .with(TestAuthHttp.csrf())
                    .header("Authorization", "Bearer " + access)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"Password123!\"}"))
            .andExpect(status().is(code))
            .andReturn();
    return code == 200 ? started(r) : null;
  }

  void confirm(Attempt a, String access, String raw, int code) throws Exception {
    mvc.perform(
            post("/auth/google/link/confirm")
                .with(TestAuthHttp.csrf())
                .header("Authorization", "Bearer " + access)
                .cookie(a.cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("sessionId", a.id, "credential", raw))))
        .andExpect(status().is(code));
  }

  @Test
  void linkingRequiresReauthenticationAndSameEmailAndDoesNotCreateUser() throws Exception {
    var u = account(Role.BRAND);
    String access = jwt.generateToken(u);
    var a = linkStart(u, access, 200);
    String subject = UUID.randomUUID().toString();
    confirm(a, access, proof(a, email(), subject), 409);
    assertThat(identities.findByUserIdAndProvider(u.getId(), ExternalIdentityProvider.GOOGLE))
        .isEmpty();
    String raw = proof(a, u.getEmail(), subject);
    confirm(a, access, raw, 200);
    confirm(a, access, raw, 409);
    assertThat(
            identities
                .findByProviderAndProviderSubject(ExternalIdentityProvider.GOOGLE, subject)
                .orElseThrow()
                .getUser()
                .getId())
        .isEqualTo(u.getId());
  }

  @Test
  void linkCannotBeConfirmedFromDifferentAuthSession() throws Exception {
    var u = account(Role.BRAND);
    String first = jwt.generateToken(u);
    var a = linkStart(u, first, 200);
    String second = jwt.generateToken(u);
    confirm(a, second, proof(a, u.getEmail(), UUID.randomUUID().toString()), 401);
    assertThat(identities.findByUserIdAndProvider(u.getId(), ExternalIdentityProvider.GOOGLE))
        .isEmpty();
  }

  @Test
  void auditFailureRollsBackIdentityAndLeavesLinkChallengeUsable() throws Exception {
    var u = account(Role.BRAND);
    String access = jwt.generateToken(u);
    var a = linkStart(u, access, 200);
    String raw = proof(a, u.getEmail(), UUID.randomUUID().toString());
    doThrow(new IllegalStateException("Audit storage unavailable"))
        .when(
            (com.influencermatch.backend.audit.service.AuditService)
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit))
        .recordAs(
            eq(u.getId()),
            eq("LINK_GOOGLE_IDENTITY"),
            anyString(),
            any(),
            any(),
            any(),
            anyString());
    confirm(a, access, raw, 500);
    assertThat(identities.findByUserIdAndProvider(u.getId(), ExternalIdentityProvider.GOOGLE))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select status from google_login_session where id=?", String.class, a.id))
        .isEqualTo("NEW");
  }

  @Test
  void wrongPasswordCannotStartLink() throws Exception {
    var u = account(Role.BRAND);
    mvc.perform(
            post("/auth/google/link/start")
                .with(TestAuthHttp.csrf())
                .header("Authorization", "Bearer " + jwt.generateToken(u))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void allowedFrontendCanPreflightAndHttpsCookiesAreHttpOnly() throws Exception {
    mvc.perform(
            options("/auth/google/login")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
        .andExpect(status().isOk());
    var r =
        mvc.perform(post("/auth/google/start").secure(true).with(TestAuthHttp.csrf()))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(r.getResponse().getHeader("Set-Cookie"))
        .contains("Secure", "HttpOnly", "SameSite=Lax");
    mvc.perform(
            options("/auth/google/login")
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }
}
