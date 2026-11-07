package com.influencermatch.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.ExternalIdentityRepository;
import com.influencermatch.backend.auth.service.GoogleIdTokenVerifier;
import com.influencermatch.backend.support.PostgresApiTestSupport;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;

class GoogleBrandLoginPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean GoogleIdTokenVerifier verifier;
  @Autowired ExternalIdentityRepository identities;

  @org.springframework.boot.test.mock.mockito.SpyBean
  com.influencermatch.backend.auth.service.RefreshTokenService refresh;

  final String client = "test.apps.googleusercontent.com";

  @BeforeEach
  void configured() {
    when(verifier.requireConfigured()).thenReturn(client);
  }

  record Attempt(UUID id, String nonce, Cookie cookie) {}

  Attempt start() throws Exception {
    var r = mvc.perform(post("/auth/google/start")).andExpect(status().isOk()).andReturn();
    JsonNode data = json.readTree(r.getResponse().getContentAsString()).path("data");
    Cookie cookie = r.getResponse().getCookie("google_login_" + data.path("sessionId").asText());
    assertThat(cookie).isNotNull();
    assertThat(cookie.isHttpOnly()).isTrue();
    return new Attempt(
        UUID.fromString(data.path("sessionId").asText()), data.path("nonce").asText(), cookie);
  }

  String proof(Attempt a, String email, String subject) {
    String credential = UUID.randomUUID().toString();
    when(verifier.verify(credential))
        .thenReturn(
            Jwt.withTokenValue("not-real-token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", "Google Brand")
                .claim("nonce", a.nonce)
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
    return credential;
  }

  MvcResult login(Attempt a, String credential, int expected) throws Exception {
    return mvc.perform(
            post("/auth/google/login")
                .cookie(a.cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(Map.of("sessionId", a.id, "credential", credential))))
        .andExpect(status().is(expected))
        .andReturn();
  }

  String email() {
    return "google-" + UUID.randomUUID() + "@gmail.com";
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

  @Test
  void createsVerifiedBrandWithoutPasswordAndReplayCannotIssueAnotherSession() throws Exception {
    Attempt a = start();
    String email = email(), subject = UUID.randomUUID().toString();
    String raw = proof(a, email, subject);
    var response =
        json.readTree(login(a, raw, 200).getResponse().getContentAsString()).path("data");
    User user = users.findByEmail(email).orElseThrow();
    assertThat(user.getRole()).isEqualTo(Role.BRAND);
    assertThat(user.isEmailVerified()).isTrue();
    assertThat(user.getPasswordHash()).isNull();
    assertThat(response.path("user").path("permissions").isArray()).isTrue();
    assertThat(response.path("accessToken").asText()).isNotBlank();
    assertThat(response.path("refreshToken").asText()).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_token where user_id=?", Integer.class, user.getId()))
        .isEqualTo(1);
    login(a, raw, 409);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_token where user_id=?", Integer.class, user.getId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select nonce_hash from google_login_session where id=?", String.class, a.id))
        .doesNotContain(a.nonce);
  }

  @Test
  void knownGoogleSubjectReusesBrandAndSupportsRefreshAndMe() throws Exception {
    User user = account(Role.BRAND);
    String subject = UUID.randomUUID().toString();
    identity(user, subject);
    Attempt a = start();
    var r =
        json.readTree(
                login(a, proof(a, user.getEmail(), subject), 200)
                    .getResponse()
                    .getContentAsString())
            .path("data");
    assertThat(r.path("user").path("id").asText()).isEqualTo(user.getId().toString());
    var me =
        callToken(
            r.path("accessToken").asText(),
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/me"),
            null,
            200);
    assertThat(me.path("data").path("permissions")).isEqualTo(r.path("user").path("permissions"));
    call(null, post("/auth/refresh"), Map.of("refreshToken", r.path("refreshToken").asText()), 200);
  }

  @Test
  void sameEmailNeverAutomaticallyLinksPasswordAccount() throws Exception {
    User user = account(Role.BRAND);
    Attempt a = start();
    login(a, proof(a, user.getEmail(), UUID.randomUUID().toString()), 409);
    assertThat(identities.findByUserIdAndProvider(user.getId(), ExternalIdentityProvider.GOOGLE))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_token where user_id=?", Integer.class, user.getId()))
        .isZero();
  }

  @Test
  void pendingOtpCannotBeActivatedByGoogle() throws Exception {
    User user = account(Role.BRAND);
    user.setEmailVerified(false);
    users.saveAndFlush(user);
    Attempt a = start();
    login(a, proof(a, user.getEmail(), UUID.randomUUID().toString()), 403);
    assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"CREATOR", "ADMIN", "DATA_MANAGER"})
  void onlyBrandCanUseKnownIdentity(Role role) throws Exception {
    User user = account(role);
    String subject = UUID.randomUUID().toString();
    identity(user, subject);
    Attempt a = start();
    login(a, proof(a, user.getEmail(), subject), 403);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_token where user_id=?", Integer.class, user.getId()))
        .isZero();
  }

  @ParameterizedTest
  @EnumSource(
      value = UserStatus.class,
      names = {"LOCKED", "DISABLED"})
  void blockedBrandCannotReceiveTokens(UserStatus status) throws Exception {
    User user = account(Role.BRAND);
    user.setStatus(status);
    users.saveAndFlush(user);
    String subject = UUID.randomUUID().toString();
    identity(user, subject);
    Attempt a = start();
    login(a, proof(a, user.getEmail(), subject), 401);
  }

  @Test
  void wrongBrowserAndNonceAndExpiredSessionCannotCreateUser() throws Exception {
    Attempt a = start();
    String email = email();
    String raw = proof(a, email, UUID.randomUUID().toString());
    login(new Attempt(a.id, a.nonce, new Cookie(a.cookie.getName(), "wrong-browser")), raw, 401);
    Attempt other = start();
    login(a, proof(other, email, UUID.randomUUID().toString()), 401);
    jdbc.update(
        "update google_login_session set expires_at=now()-interval '1 second' where id=?", a.id);
    login(a, raw, 401);
    assertThat(users.findByEmail(email)).isEmpty();
  }

  @Test
  void simultaneousLoginConsumesSessionOnlyOnce() throws Exception {
    Attempt a = start();
    String email = email(), raw = proof(a, email, UUID.randomUUID().toString());
    var gate = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> task =
          () -> {
            gate.await();
            return mvc.perform(
                    post("/auth/google/login")
                        .cookie(a.cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsBytes(Map.of("sessionId", a.id, "credential", raw))))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var first = executor.submit(task);
      var second = executor.submit(task);
      gate.countDown();
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      User user = users.findByEmail(email).orElseThrow();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from refresh_token where user_id=?",
                  Integer.class,
                  user.getId()))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void issuingSessionFailureRollsBackNewUserIdentityAndConsumption() throws Exception {
    Attempt a = start();
    String email = email(), raw = proof(a, email, UUID.randomUUID().toString());
    doThrow(
            new com.influencermatch.backend.exception.BusinessException(
                com.influencermatch.backend.exception.ErrorCode.UNAUTHENTICATED,
                "Simulated failure"))
        .when(refresh)
        .issue(any(User.class));
    login(a, raw, 401);
    assertThat(users.findByEmail(email)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select consumed_at is null from google_login_session where id=?",
                Boolean.class,
                a.id))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from external_identity where provider_email=?",
                Integer.class,
                email))
        .isZero();
  }

  @Test
  void invalidProviderCredentialCannotCreateAccount() throws Exception {
    Attempt a = start();
    when(verifier.verify("invalid"))
        .thenThrow(
            new com.influencermatch.backend.exception.BusinessException(
                com.influencermatch.backend.exception.ErrorCode.UNAUTHENTICATED,
                "Invalid Google credential"));
    login(a, "invalid", 401);
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
                "Google Login is not configured"));
    mvc.perform(post("/auth/google/start")).andExpect(status().isServiceUnavailable());
  }

  @Test
  void allowedFrontendCanPreflightGoogleLoginAndHttpsCookieIsSecure() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                    "/auth/google/login")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Access-Control-Allow-Credentials", "true"));
    var result =
        mvc.perform(post("/auth/google/start").secure(true)).andExpect(status().isOk()).andReturn();
    assertThat(result.getResponse().getHeader("Set-Cookie"))
        .contains("Secure", "HttpOnly", "SameSite=Lax");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                    "/auth/google/login")
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }
}
