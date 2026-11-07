package com.influencermatch.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@TestPropertySource(
    properties = {
      "application.auth.email-verification.secret=auth-target-test-secret-at-least-32-characters",
      "application.auth.cookie-secure=false"
    })
class AuthTargetPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean VerificationEmailSender sender;

  record Session(String access, String refresh, JsonNode user) {}

  Map<String, Object> registration(String email) {
    return new LinkedHashMap<>(
        Map.of(
            "email",
            email,
            "password",
            "Password123!",
            "fullName",
            "Brand Owner",
            "businessName",
            "Real Brand",
            "phoneNumber",
            "0912345678",
            "policyAcceptance",
            TestAuthHttp.acceptance()));
  }

  MvcResult postJson(String path, Object body, String token, String refresh, int code)
      throws Exception {
    MockHttpServletRequestBuilder r =
        post(path).with(TestAuthHttp.csrf()).contentType(MediaType.APPLICATION_JSON);
    if (body != null) r.content(json.writeValueAsBytes(body));
    if (token != null) r.header("Authorization", "Bearer " + token);
    if (refresh != null) r.cookie(new Cookie("IM_REFRESH", refresh));
    return mvc.perform(r).andExpect(status().is(code)).andReturn();
  }

  Session session(User u) throws Exception {
    var r =
        postJson(
            "/auth/login",
            Map.of("email", u.getEmail(), "password", "Password123!"),
            null,
            null,
            200);
    JsonNode data = json.readTree(r.getResponse().getContentAsString()).path("data");
    assertThat(data.has("refreshToken")).isFalse();
    var cookie = r.getResponse().getCookie("IM_REFRESH");
    assertThat(cookie).isNotNull();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
    return new Session(data.path("accessToken").asText(), cookie.getValue(), data.path("user"));
  }

  @Test
  void registrationCreatesProfileContextAndTwoConsentsWithoutSession() throws Exception {
    String email = "register-" + UUID.randomUUID() + "@example.test";
    postJson("/auth/register", registration(email), null, null, 201);
    User u = users.findByEmail(email).orElseThrow();
    assertThat(u.isEmailVerified()).isFalse();
    UUID profile =
        jdbc.queryForObject("select id from brand_profile where user_id=?", UUID.class, u.getId());
    assertThat(
            jdbc.queryForObject(
                "select phone_number from brand_profile where id=?", String.class, profile))
        .isEqualTo("+84912345678");
    assertThat(
            jdbc.queryForObject(
                "select context_version from brand_context where brand_profile_id=?",
                Integer.class,
                profile))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_policy_acceptance where user_id=?",
                Integer.class,
                u.getId()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_session where user_id=?", Integer.class, u.getId()))
        .isZero();
  }

  @Test
  void v25PreservesHistoricalAccountsAndOwnershipButInvalidatesOldSessions() {
    String schema = "auth_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var config =
        org.flywaydb.core.Flyway.configure()
            .dataSource(jdbc.getDataSource())
            .schemas(schema)
            .defaultSchema(schema);
    config.target("24").load().migrate();
    try {
      UUID user = UUID.randomUUID(), master = UUID.randomUUID(), profile = UUID.randomUUID();
      jdbc.update(
          "insert into "
              + schema
              + ".\"user\"(id,email,full_name,role,status,created_at,updated_at,email_verified,auth_version)"
              + " values(?,?,'Historical','BRAND','ACTIVE',now(),now(),true,7)",
          user,
          user + "@example.test");
      jdbc.update(
          "insert into "
              + schema
              + ".creator_master(id,user_id,canonical_name,status,created_at,updated_at)"
              + " values(?,?,'Master','ACTIVE',now(),now())",
          master,
          user);
      jdbc.update(
          "insert into "
              + schema
              + ".brand_profile(id,user_id,business_name,created_at,updated_at)"
              + " values(?,?,'Existing',now(),now())",
          profile,
          user);
      jdbc.update(
          "insert into "
              + schema
              + ".external_identity(id,user_id,provider,provider_subject,provider_email,email_verified,created_at,updated_at)"
              + " values(?,?,'GOOGLE','keep-subject','historical@example.test',true,now(),now())",
          UUID.randomUUID(),
          user);
      jdbc.update(
          "insert into "
              + schema
              + ".refresh_token(id,user_id,token_hash,jti,expires_at,created_at)"
              + " values(?,?,repeat('a',64),?,now()+interval '30 days',now())",
          UUID.randomUUID(),
          user,
          UUID.randomUUID());
      assertThat(config.target("25").load().migrate().migrationsExecuted).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select auth_version from " + schema + ".\"user\" where id=?", Long.class, user))
          .isEqualTo(8);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from "
                      + schema
                      + ".refresh_token where revoked_at is not null and session_id is null",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select onboarding_status from " + schema + ".brand_profile where id=?",
                  String.class,
                  profile))
          .isEqualTo("PENDING");
      assertThat(
              jdbc.queryForObject(
                  "select phone_number from " + schema + ".brand_profile where id=?",
                  String.class,
                  profile))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + schema + ".user_policy_acceptance", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select user_id from " + schema + ".creator_master where id=?",
                  UUID.class,
                  master))
          .isEqualTo(user);
      assertThat(
              jdbc.queryForObject(
                  "select provider_subject from " + schema + ".external_identity where user_id=?",
                  String.class,
                  user))
          .isEqualTo("keep-subject");
    } finally {
      jdbc.execute("drop schema " + schema + " cascade");
    }
  }

  @Test
  void invalidConsentAndVersionAndPhoneLeaveNoData() throws Exception {
    for (String condition : List.of("missing", "false", "old", "phone")) {
      String email = "invalid-" + UUID.randomUUID() + "@example.test";
      var body = registration(email);
      if (condition.equals("missing")) body.remove("policyAcceptance");
      if (condition.equals("false"))
        body.put(
            "policyAcceptance",
            new com.influencermatch.backend.auth.dto.PolicyAcceptance(
                false, "terms-v1", true, "privacy-v1"));
      if (condition.equals("old"))
        body.put(
            "policyAcceptance",
            new com.influencermatch.backend.auth.dto.PolicyAcceptance(
                true, "old", true, "privacy-v1"));
      if (condition.equals("phone")) body.put("phoneNumber", "abc");
      postJson("/auth/register", body, null, null, condition.equals("old") ? 409 : 400);
      assertThat(users.findByEmail(email)).isEmpty();
    }
  }

  @Test
  void smtpFailureRollsBackProfileConsentAndUser() throws Exception {
    String email = "smtp-" + UUID.randomUUID() + "@example.test";
    doThrow(new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE, "SMTP unavailable"))
        .when(sender)
        .send(eq(email), anyString());
    postJson("/auth/register", registration(email), null, null, 503);
    assertThat(users.findByEmail(email)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from brand_profile p join \"user\" u on u.id=p.user_id where"
                    + " u.email=?",
                Integer.class,
                email))
        .isZero();
  }

  @Test
  void missingCsrfIs403AndRealCsrfEndpointAllowsLogin() throws Exception {
    var u = account(Role.BRAND);
    byte[] body = json.writeValueAsBytes(Map.of("email", u.getEmail(), "password", "Password123!"));
    mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
    var csrf = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
    var data = json.readTree(csrf.getContentAsString()).path("data");
    mvc.perform(
            post("/auth/login")
                .cookie(csrf.getCookie("IM_CSRF"))
                .header(data.path("headerName").asText(), data.path("token").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void refreshReadsOnlyCookieAndCannotExtendSession() throws Exception {
    var u = account(Role.BRAND);
    var s = session(u);
    var expiry =
        jdbc.queryForObject(
            "select expires_at from auth_session where user_id=?",
            java.sql.Timestamp.class,
            u.getId());
    postJson("/auth/refresh", Map.of("refreshToken", s.refresh), null, null, 401);
    var refreshed = postJson("/auth/refresh", null, null, s.refresh, 200).getResponse();
    assertThat(json.readTree(refreshed.getContentAsString()).path("data").has("refreshToken"))
        .isFalse();
    assertThat(refreshed.getCookie("IM_REFRESH").getValue()).isNotEqualTo(s.refresh);
    assertThat(
            jdbc.queryForObject(
                "select max(expires_at) from refresh_token where user_id=?",
                java.sql.Timestamp.class,
                u.getId()))
        .isEqualTo(expiry);
  }

  @Test
  void logoutCurrentSessionImmediatelyRejectsItsJwtButKeepsOtherDevice() throws Exception {
    var u = account(Role.BRAND);
    var first = session(u);
    var second = session(u);
    postJson("/auth/logout", null, null, first.refresh, 204);
    callToken(first.access, get("/auth/me"), null, 401);
    callToken(second.access, get("/auth/me"), null, 200);
    postJson("/auth/logout", null, null, first.refresh, 204);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_session where user_id=? and revoked_at is null",
                Integer.class,
                u.getId()))
        .isEqualTo(1);
  }

  @Test
  void logoutWorksWithExpiredAccessTokenAndDeletesCookie() throws Exception {
    var u = account(Role.BRAND);
    var s = session(u);
    UUID sid =
        jdbc.queryForObject("select id from auth_session where user_id=?", UUID.class, u.getId());
    String expired = jwt.generateToken(u, sid, java.time.Instant.now().minusSeconds(60));
    var r = postJson("/auth/logout", null, expired, s.refresh, 204).getResponse();
    assertThat(r.getCookie("IM_REFRESH").getMaxAge()).isZero();
    callToken(s.access, get("/auth/me"), null, 401);
  }

  @Test
  void logoutAllInvalidatesBothDevicesAndIncrementsVersion() throws Exception {
    var u = account(Role.BRAND);
    var first = session(u);
    var second = session(u);
    postJson("/auth/logout-all", null, first.access, first.refresh, 204);
    callToken(first.access, get("/auth/me"), null, 401);
    callToken(second.access, get("/auth/me"), null, 401);
    assertThat(users.findById(u.getId()).orElseThrow().getAuthVersion())
        .isEqualTo(u.getAuthVersion() + 1);
  }

  @Test
  void replayCommitsUserWideRevocationAndDoesNotAffectOtherUser() throws Exception {
    var u = account(Role.BRAND);
    var first = session(u);
    var second = session(u);
    var other = session(account(Role.BRAND));
    postJson("/auth/refresh", null, null, first.refresh, 200);
    postJson("/auth/refresh", null, null, first.refresh, 401);
    callToken(second.access, get("/auth/me"), null, 401);
    callToken(other.access, get("/auth/me"), null, 200);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_session where user_id=? and revoked_at is null",
                Integer.class,
                u.getId()))
        .isZero();
  }

  @Test
  void expiredSessionRejectsAccessAndRefresh() throws Exception {
    var u = account(Role.BRAND);
    var s = session(u);
    jdbc.update(
        "update auth_session set expires_at=now()-interval '7 days',created_at=now()-interval '8"
            + " days' where user_id=?",
        u.getId());
    callToken(s.access, get("/auth/me"), null, 401);
    postJson("/auth/refresh", null, null, s.refresh, 401);
  }

  @Test
  void completeOnboardingRequiresContextAndCannotBeClearedAfterCompletion() throws Exception {
    String email = "onboard-" + UUID.randomUUID() + "@example.test";
    postJson("/auth/register", registration(email), null, null, 201);
    var u = users.findByEmail(email).orElseThrow();
    u.setEmailVerified(true);
    users.saveAndFlush(u);
    var s = session(u);
    assertThat(s.user.path("onboardingStatus").asText()).isEqualTo("PENDING");
    var failed = postJson("/brands/me/onboarding/complete", null, s.access, null, 400);
    assertThat(
            json.readTree(failed.getResponse().getContentAsString())
                .path("fieldErrors")
                .has("industry"))
        .isTrue();
    var profile =
        Map.of(
            "businessName",
            "Real Brand",
            "phoneNumber",
            "0912345678",
            "industry",
            "Beauty",
            "productCategories",
            List.of("Skincare"),
            "targetAudiences",
            List.of("Adults"),
            "brandTone",
            "Friendly",
            "preferredPlatforms",
            List.of("TIKTOK"));
    callToken(s.access, put("/brands/me"), profile, 200);
    var complete =
        json.readTree(
                postJson("/brands/me/onboarding/complete", null, s.access, null, 200)
                    .getResponse()
                    .getContentAsString())
            .path("data");
    assertThat(complete.path("onboardingStatus").asText()).isEqualTo("COMPLETED");
    var again =
        json.readTree(
                postJson("/brands/me/onboarding/complete", null, s.access, null, 200)
                    .getResponse()
                    .getContentAsString())
            .path("data");
    assertThat(again.path("onboardingCompletedAt"))
        .isEqualTo(complete.path("onboardingCompletedAt"));
    callToken(s.access, put("/brands/me"), Map.of("businessName", "Real Brand"), 400);
    assertThat(
            callToken(s.access, get("/auth/me"), null, 200)
                .path("data")
                .path("onboardingStatus")
                .asText())
        .isEqualTo("COMPLETED");
  }
}
