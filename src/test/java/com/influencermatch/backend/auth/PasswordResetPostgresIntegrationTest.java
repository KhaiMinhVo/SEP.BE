package com.influencermatch.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.influencermatch.backend.auth.service.EmailOtpCodes;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.support.TestAuthHttp;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties =
    "application.auth.email-verification.secret=test-email-otp-secret-at-least-32-characters")
class PasswordResetPostgresIntegrationTest extends PostgresApiTestSupport {
  private static final String BASE = "/auth/password-reset";
  private static final String OLD_PASSWORD = "Password123!";
  private static final String NEW_PASSWORD = "NewPassword123!";
  private static final String OTP = "000042";

  @MockBean VerificationEmailSender sender;
  @MockBean Clock clock;
  @SpyBean EmailOtpCodes crypto;

  @BeforeEach
  void setTimeAndCode() {
    when(clock.instant()).thenReturn(Instant.now());
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    doReturn(OTP).when(crypto).generate();
  }

  @Test
  void acknowledgesEligibleUnknownAndIneligibleAccountsIdentically() throws Exception {
    var brand = account(Role.BRAND);
    var creator = account(Role.CREATOR);
    var admin = account(Role.ADMIN);
    var manager = account(Role.DATA_MANAGER);
    var inactive = account(Role.BRAND);
    inactive.setStatus(UserStatus.LOCKED);
    var unverified = account(Role.CREATOR);
    unverified.setEmailVerified(false);
    var googleOnly = account(Role.BRAND);
    googleOnly.setPasswordHash(null);
    users.saveAllAndFlush(java.util.List.of(inactive, unverified, googleOnly));

    var accepted = request(brand.getEmail().toUpperCase(Locale.ROOT), 202);
    assertThat(accepted.path("data").path("message").asText())
        .contains("Nếu tài khoản đủ điều kiện");
    for (var other : java.util.List.of(creator, admin, manager, inactive, unverified, googleOnly)) {
      assertThat(request(other.getEmail(), 202)).isEqualTo(accepted);
    }
    assertThat(request(UUID.randomUUID() + "@unit.test", 202)).isEqualTo(accepted);
    assertThat(request(brand.getEmail(), 202)).isEqualTo(accepted);
    verify(sender).sendPasswordReset(brand.getEmail(), OTP);
    verify(sender).sendPasswordReset(creator.getEmail(), OTP);
    verify(sender, times(2)).sendPasswordReset(anyString(), anyString());
  }

  @Test
  void onlyOneConcurrentConfirmationWinsAndOldSessionsStopWorking() throws Exception {
    var user = account(Role.CREATOR);
    var first = login(user, OLD_PASSWORD, 200);
    var second = login(user, OLD_PASSWORD, 200);
    request(user.getEmail(), 202);

    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Integer> confirm = () -> {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timed out");
        return confirm(user, OTP).getResponse().getStatus();
      };
      var a = pool.submit(confirm);
      var b = pool.submit(confirm);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(204, 400);
    }

    assertThat(users.findById(user.getId()).orElseThrow().getAuthVersion())
        .isEqualTo(user.getAuthVersion() + 1);
    for (var session : java.util.List.of(first, second)) {
      var token = json.readTree(session.getResponse().getContentAsString()).path("data").path("accessToken").asText();
      callToken(token, get("/auth/me"), null, 401);
      mvc.perform(post("/auth/refresh").with(TestAuthHttp.csrf())
          .cookie(session.getResponse().getCookie("IM_REFRESH"))).andExpect(status().isUnauthorized());
    }
    login(user, OLD_PASSWORD, 401);
    login(user, NEW_PASSWORD, 200);
  }

  @Test
  void enforcesExpiryAttemptsResendAndAuthVersion() throws Exception {
    var user = account(Role.BRAND);
    Instant issuedAt = clock.instant();
    request(user.getEmail(), 202);

    jdbc.update("update password_reset_otp set code_hash=? where user_id=?",
        crypto.hash(user.getId(), "password-setup", OTP), user.getId());
    assertThat(confirm(user, OTP).getResponse().getStatus()).isEqualTo(400);

    when(clock.instant()).thenReturn(issuedAt.plusSeconds(600));
    assertThat(confirm(user, OTP).getResponse().getStatus()).isEqualTo(400);
    doReturn("000043").when(crypto).generate();
    request(user.getEmail(), 202);
    for (int i = 0; i < 4; i++) assertThat(confirm(user, OTP).getResponse().getStatus()).isEqualTo(400);
    assertThat(confirm(user, "999999").getResponse().getStatus()).isEqualTo(400);
    assertThat(confirm(user, "000043").getResponse().getStatus()).isEqualTo(400);

    when(clock.instant()).thenReturn(issuedAt.plusSeconds(661));
    doReturn("000044").when(crypto).generate();
    request(user.getEmail(), 202);
    jdbc.update("update \"user\" set auth_version=auth_version+1 where id=?", user.getId());
    assertThat(confirm(user, "000044").getResponse().getStatus()).isEqualTo(400);

    when(clock.instant()).thenReturn(issuedAt.plusSeconds(722));
    doReturn("000045").when(crypto).generate();
    request(user.getEmail(), 202);
    jdbc.update("update \"user\" set status='LOCKED' where id=?", user.getId());
    assertThat(confirm(user, "000045").getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void deliveryFailureDoesNotLeaveAUsableCodeOrConsumeSendQuota() throws Exception {
    var user = account(Role.BRAND);
    doThrow(new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE, "SMTP failure"))
        .doNothing().when(sender).sendPasswordReset(user.getEmail(), OTP);
    request(user.getEmail(), 202);
    assertThat(confirm(user, OTP).getResponse().getStatus()).isEqualTo(400);
    request(user.getEmail(), 202);
    assertThat(confirm(user, OTP).getResponse().getStatus()).isEqualTo(204);
    verify(sender, times(2)).sendPasswordReset(user.getEmail(), OTP);

    doThrow(new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE, "SMTP unavailable"))
        .when(sender).requireConfigured();
    assertThat(request(user.getEmail(), 503))
        .isEqualTo(request(UUID.randomUUID() + "@unit.test", 503));
  }

  private com.fasterxml.jackson.databind.JsonNode request(String email, int expected) throws Exception {
    var response = (com.fasterxml.jackson.databind.node.ObjectNode) call(null, post(BASE + "/request"), Map.of("email", email), expected);
    response.remove(java.util.List.of("meta", "traceId", "timestamp"));
    return response;
  }

  private MvcResult confirm(User user, String otp) throws Exception {
    return mvc.perform(post(BASE + "/confirm").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(Map.of("email", user.getEmail(), "otp", otp, "newPassword", NEW_PASSWORD))))
        .andReturn();
  }

  private MvcResult login(User user, String password, int expected) throws Exception {
    return mvc.perform(post("/auth/login").with(TestAuthHttp.csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(Map.of("email", user.getEmail(), "password", password))))
        .andExpect(status().is(expected)).andReturn();
  }
}
