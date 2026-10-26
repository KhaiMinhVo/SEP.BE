package com.influencermatch.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@org.springframework.test.context.TestPropertySource(properties="application.auth.email-verification.secret=test-email-otp-secret-at-least-32-characters")
class AuthSessionPostgresIntegrationTest extends PostgresApiTestSupport {
  @org.springframework.boot.test.mock.mockito.MockBean com.influencermatch.backend.auth.service.VerificationEmailSender sender;
  @org.springframework.beans.factory.annotation.Autowired
  com.influencermatch.backend.auth.service.AuthService authService;
  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager transactions;

  @Test void registrationNormalizesCaseAndRejectsInjectedRole() throws Exception {
    String email="CASE-"+UUID.randomUUID()+"@EXAMPLE.TEST";
    var body=Map.of("email",email,"password","Password123!","fullName","  Test User  ","role","ADMIN");
    call(null,post("/auth/register"),body,400);
    assertThat(users.findByEmail(email.toLowerCase(Locale.ROOT))).isEmpty();
    body=Map.of("email",email,"password","Password123!","fullName","  Test User  ");
    call(null,post("/auth/register"),body,201);
    var user=users.findByEmail(email.toLowerCase(Locale.ROOT)).orElseThrow();
    assertThat(user.getRole()).isEqualTo(Role.BRAND);assertThat(user.getFullName()).isEqualTo("Test User");
    assertThat(encoder.matches("Password123!",user.getPasswordHash())).isTrue();
    var duplicate=call(null,post("/auth/register"),body,409);
    assertThat(duplicate.path("code").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");
    assertThat(jdbc.queryForObject("select count(*) from brand_profile where user_id=?",Long.class,user.getId())).isZero();
  }
  @Test void wrongPasswordDoesNotIssueSessionAndLoginNormalizesEmail() throws Exception {
    var user=account(Role.BRAND);
    call(null,post("/auth/login"),Map.of("email",user.getEmail(),"password","Wrong123!"),401);
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=?",Long.class,user.getId())).isZero();
    var session=call(null,post("/auth/login"),Map.of("email",user.getEmail().toUpperCase(Locale.ROOT),"password","Password123!"),200).path("data");
    callToken(session.path("accessToken").asText(),get("/auth/me"),null,200);
  }
  @ParameterizedTest @EnumSource(value=UserStatus.class,names={"LOCKED","DISABLED"})
  void inactiveAccountsCannotLoginOrRefresh(UserStatus status) throws Exception {
    var user=account(Role.BRAND);var session=login(user);user.setStatus(status);users.saveAndFlush(user);
    call(null,post("/auth/login"),Map.of("email",user.getEmail(),"password","Password123!"),401);
    call(null,post("/auth/refresh"),Map.of("refreshToken",session.path("refreshToken").asText()),401);
    callToken(session.path("accessToken").asText(),get("/auth/me"),null,401);
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=?",Long.class,user.getId())).isEqualTo(1);
  }
  @Test void expiredAndUnknownRefreshTokensCannotCreateSessions() throws Exception {
    var user=account(Role.BRAND);var session=login(user);
    jdbc.update("update refresh_token set expires_at=timestamp '2000-01-01 00:00:00' where user_id=?",user.getId());
    for(String raw:List.of(session.path("refreshToken").asText(),"unknown-token"))
      assertThat(call(null,post("/auth/refresh"),Map.of("refreshToken",raw),401).path("code").asText()).isEqualTo("UNAUTHENTICATED");
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=?",Long.class,user.getId())).isEqualTo(1);
  }
  @Test void rotationLinksOldTokenAndLogoutRevokesReplacement() throws Exception {
    var user=account(Role.BRAND);var session=login(user);
    var rotated=call(null,post("/auth/refresh"),Map.of("refreshToken",session.path("refreshToken").asText()),200).path("data");
    assertThat(rotated.path("refreshToken").asText()).isNotEqualTo(session.path("refreshToken").asText());
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=? and revoked_at is not null and replaced_by_token_id is not null",Long.class,user.getId())).isEqualTo(1);
    callToken(rotated.path("accessToken").asText(),post("/auth/logout"),Map.of("refreshToken",rotated.path("refreshToken").asText()),204);
    call(null,post("/auth/refresh"),Map.of("refreshToken",rotated.path("refreshToken").asText()),401);
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=? and revoked_at is null",Long.class,user.getId())).isZero();
  }
  @Test void replayedRotatedTokenRevokesAllOtherSessionsEvenWhenRequestIsRejected() throws Exception {
    var user=account(Role.BRAND);var first=login(user);var second=login(user);
    var other=account(Role.BRAND);var unaffected=login(other);
    var rotated=call(null,post("/auth/refresh"),Map.of("refreshToken",first.path("refreshToken").asText()),200).path("data");
    long rows=jdbc.queryForObject("select count(*) from refresh_token where user_id=?",Long.class,user.getId());
    call(null,post("/auth/refresh"),Map.of("refreshToken",first.path("refreshToken").asText()),401);
    // Contract already expressed by RefreshTokenService.rotate and its existing Mockito test:
    // replay must revoke every refresh token, and that revocation must survive the 401 response.
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=? and revoked_at is null",Long.class,user.getId()))
        .as("Replayed token must revoke all active sessions in the database").isZero();
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=?",Long.class,user.getId())).isEqualTo(rows);
    for(String token:List.of(second.path("refreshToken").asText(),rotated.path("refreshToken").asText()))
      call(null,post("/auth/refresh"),Map.of("refreshToken",token),401);
    // Defensive revocation is scoped to the replayed account, not all users.
    call(null,post("/auth/refresh"),Map.of("refreshToken",unaffected.path("refreshToken").asText()),200);
  }
  @ParameterizedTest @ValueSource(strings={"malformed","Bearer invalid.jwt.token"})
  void invalidAuthorizationIs401(String authorization) throws Exception {
    mvc.perform(get("/auth/me").header("Authorization",authorization))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  }
  @Test void successfulRotationStillRollsBackWithItsCallerTransaction() throws Exception {
    var user=account(Role.BRAND);var session=login(user);String raw=session.path("refreshToken").asText();
    var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
    assertThatThrownBy(()->transaction.executeWithoutResult(status->{
      authService.refresh(new com.influencermatch.backend.auth.dto.RefreshTokenRequest(raw));
      throw new IllegalStateException("force caller rollback");
    })).isInstanceOf(IllegalStateException.class).hasMessage("force caller rollback");
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=?",Long.class,user.getId())).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=? and revoked_at is null and replaced_by_token_id is null",Long.class,user.getId())).isEqualTo(1);
    call(null,post("/auth/refresh"),Map.of("refreshToken",raw),200);
  }
  @Test void concurrentRefreshReplayCommitsRevocationWithoutDeadlock() throws Exception {
    var user=account(Role.BRAND);var session=login(user);var body=Map.of("refreshToken",session.path("refreshToken").asText());
    var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var results=new ArrayList<java.util.concurrent.Future<Integer>>();
      for(int i=0;i<2;i++)results.add(pool.submit(()->{
        start.await();
        return mvc.perform(post("/auth/refresh").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(body))).andReturn().getResponse().getStatus();
      }));
      start.countDown();
      assertThat(List.of(results.get(0).get(30,java.util.concurrent.TimeUnit.SECONDS),results.get(1).get(30,java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200,401);
      assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=? and revoked_at is null",Long.class,user.getId())).isZero();
    } finally {pool.shutdownNow();}
  }

}
