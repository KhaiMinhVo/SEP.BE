package com.influencermatch.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties="application.auth.email-verification.secret=test-email-otp-secret-at-least-32-characters")
class EmailVerificationPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean VerificationEmailSender sender;
  @MockBean Clock clock;
  @SpyBean EmailOtpCodes crypto;
  @Autowired RefreshTokenService refresh;
  Instant now=Instant.parse("2026-10-22T00:00:00Z");
  String email;
  @BeforeEach void setup() {
    email=UUID.randomUUID()+"@example.test";
    when(clock.instant()).thenReturn(now);doReturn("000042").when(crypto).generate();
  }
  Map<String,Object> registration() {return Map.of("email",email,"password","Password123!","fullName","Brand");}
  User register() throws Exception {
    var response=call(null,post("/auth/register"),registration(),201);
    assertThat(response.toString()).doesNotContain("000042","accessToken","refreshToken");
    return users.findByEmail(email).orElseThrow();
  }
  void verifyCode(String code,int status) throws Exception {call(null,post("/auth/verify-email"),Map.of("email",email,"otp",code),status);}
  void resend(int status) throws Exception {call(null,post("/auth/resend-verification"),Map.of("email",email),status);}
  long sessions(User user) {return jdbc.queryForObject("select count(*) from refresh_token where user_id=?",Long.class,user.getId());}

  @Test void registrationMustBeVerifiedBeforeLoginAndTokensAreNeverLeaked() throws Exception {
    var user=register();assertThat(user.getRole()).isEqualTo(Role.BRAND);assertThat(user.isEmailVerified()).isFalse();
    assertThat(encoder.matches("Password123!",user.getPasswordHash())).isTrue();
    call(null,post("/auth/login"),Map.of("email",email,"password","Wrong123!"),401);
    assertThat(call(null,post("/auth/login"),Map.of("email",email,"password","Password123!"),403).path("code").asText()).isEqualTo("EMAIL_NOT_VERIFIED");
    assertThat(sessions(user)).isZero();call(user,get("/auth/me"),null,401);
    assertThat(jdbc.queryForObject("select code_hash from email_verification_otp where user_id=?",String.class,user.getId()))
        .hasSize(64).isNotEqualTo("000042");
    verifyCode("000042",200);assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();
    assertThat(jdbc.queryForObject("select consumed_at from email_verification_otp where user_id=?",java.sql.Timestamp.class,user.getId())).isNotNull();
    var session=login(user);assertThat(session.path("accessToken").asText()).isNotBlank();assertThat(sessions(user)).isEqualTo(1);
    callToken(session.path("accessToken").asText(),get("/auth/me"),null,200);verifyCode("000042",409);
    call(null,post("/auth/register"),registration(),409);verify(sender,times(1)).send(email,"000042");
  }
  @Test void failedAttemptsCommitAndFifthAttemptLocksEvenTheCorrectCode() throws Exception {
    var user=register();
    for(int i=1;i<=5;i++) {
      verifyCode("999999",i==5?429:400);
      assertThat(jdbc.queryForObject("select attempts from email_verification_otp where user_id=?",Integer.class,user.getId())).isEqualTo(i);
    }
    verifyCode("000042",429);assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();assertThat(sessions(user)).isZero();
  }
  @Test void codeExpiresAtTenMinutesAndCanBeReplacedWithoutSleep() throws Exception {
    var user=register();when(clock.instant()).thenReturn(now.plusSeconds(600));verifyCode("000042",400);
    assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
    doReturn("000043").when(crypto).generate();resend(200);verifyCode("000043",200);
  }
  @Test void resendCooldownAndReplacementInvalidateOldCode() throws Exception {
    var user=register();var hash=jdbc.queryForObject("select code_hash from email_verification_otp where user_id=?",String.class,user.getId());
    when(clock.instant()).thenReturn(now.plusSeconds(59));resend(429);
    assertThat(jdbc.queryForObject("select code_hash from email_verification_otp where user_id=?",String.class,user.getId())).isEqualTo(hash);
    when(clock.instant()).thenReturn(now.plusSeconds(60));doReturn("000043").when(crypto).generate();resend(200);
    verifyCode("000042",400);verifyCode("000043",200);
  }
  @Test void fiveEmailsPerRollingHourIncludesRegistration() throws Exception {
    var user=register();doReturn("000043","000044","000045","000046","000047").when(crypto).generate();for(int i=1;i<5;i++) {when(clock.instant()).thenReturn(now.plusSeconds(i*60));resend(200);}
    when(clock.instant()).thenReturn(now.plusSeconds(300));resend(429);
    assertThat(jdbc.queryForObject("select count(*) from email_verification_delivery where user_id=?",Long.class,user.getId())).isEqualTo(5);
    when(clock.instant()).thenReturn(now.plusSeconds(3601));resend(200);verify(sender,times(6)).send(eq(email),anyString());
  }
  @Test void resendOfUnknownOrVerifiedEmailUsesSameEnvelopeAndDoesNotSend() throws Exception {
    var unknown=call(null,post("/auth/resend-verification"),Map.of("email",email),200);
    register();verifyCode("000042",200);clearInvocations(sender);
    var verified=call(null,post("/auth/resend-verification"),Map.of("email",email),200);
    assertThat(verified.path("success")).isEqualTo(unknown.path("success"));
    assertThat(verified.path("data")).isEqualTo(unknown.path("data"));verify(sender,never()).send(anyString(),anyString());
  }
  @ParameterizedTest @ValueSource(strings={"configuration","send"})
  void registrationFailureRollsBackAccountAndCode(String failure) throws Exception {
    var ex=new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unavailable");
    if(failure.equals("configuration"))doThrow(ex).when(sender).requireConfigured();else doThrow(ex).when(sender).send(anyString(),anyString());
    call(null,post("/auth/register"),registration(),503);assertThat(users.findByEmail(email)).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from email_verification_otp o left join \"user\" u on u.id=o.user_id where u.id is null",Long.class)).isZero();
  }
  @Test void smtpFailureDuringResendRollsBackHashCountersAndDelivery() throws Exception {
    var user=register();var before=jdbc.queryForMap("select * from email_verification_otp where user_id=?",user.getId());
    when(clock.instant()).thenReturn(now.plusSeconds(60));doReturn("000043").when(crypto).generate();
    doThrow(new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unavailable")).when(sender).send(email,"000043");
    resend(503);assertThat(jdbc.queryForMap("select * from email_verification_otp where user_id=?",user.getId())).isEqualTo(before);
    assertThat(jdbc.queryForObject("select count(*) from email_verification_delivery where user_id=?",Long.class,user.getId())).isEqualTo(1);
    verifyCode("000042",200);
  }
  @ParameterizedTest @NullSource @ValueSource(strings={"","12345","1234567","abcdef"," 000042"})
  void invalidOtpPayloadNeverChangesAttemptCount(String code) throws Exception {
    var user=register();var body=new HashMap<String,Object>();body.put("email",email);body.put("otp",code);
    call(null,post("/auth/verify-email"),body,400);
    assertThat(jdbc.queryForObject("select attempts from email_verification_otp where user_id=?",Integer.class,user.getId())).isZero();
  }
  @Test void unknownEmailAndMissingOtpStateCannotBeVerified() throws Exception {
    verifyCode("000042",400);var user=account(Role.BRAND);user.setEmailVerified(false);users.saveAndFlush(user);email=user.getEmail();
    verifyCode("000042",400);assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
  }
  @Test void concurrentVerificationConsumesCodeOnlyOnce() throws Exception {
    var user=register();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
    try {
      Callable<Integer> task=()->{gate.await();return mvc.perform(post("/auth/verify-email").contentType("application/json")
          .content(json.writeValueAsBytes(Map.of("email",email,"otp","000042")))).andReturn().getResponse().getStatus();};
      var first=pool.submit(task);var second=pool.submit(task);gate.countDown();
      assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
      assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();
    } finally {pool.shutdownNow();}
  }
  @Test void pendingAccountCannotBeActivatedByAdminStatusOrRole() throws Exception {
    var user=register();var admin=account(Role.ADMIN);
    assertThat(jdbc.queryForObject("select count(*) from external_identity where user_id=?",Long.class,user.getId())).isZero();
    call(admin,patch("/admin/users/"+user.getId()+"/status"),Map.of("status","LOCKED","reason","Test"),200);
    call(admin,patch("/admin/users/"+user.getId()+"/status"),Map.of("status","ACTIVE","reason","Test"),200);
    call(admin,patch("/admin/users/"+user.getId()+"/role"),Map.of("role","DATA_MANAGER","reason","Test"),200);
    call(null,post("/auth/login"),Map.of("email",email,"password","Password123!"),403);
    assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();assertThat(sessions(user)).isZero();
  }
  @Test void existingAccountsRemainVerifiedButRefreshCannotBypassVerification() throws Exception {
    var existing=account(Role.BRAND);assertThat(existing.isEmailVerified()).isTrue();var session=login(existing);
    existing.setEmailVerified(false);users.saveAndFlush(existing);
    callToken(session.path("accessToken").asText(),get("/auth/me"),null,401);
    call(null,post("/auth/refresh"),Map.of("refreshToken",session.path("refreshToken").asText()),401);
    assertThatThrownBy(()->refresh.issue(existing)).isInstanceOf(BusinessException.class);assertThat(sessions(existing)).isEqualTo(1);

  }
  @Test void concurrentAdminRoleChangeCannotOverwriteSuccessfulVerification() throws Exception {
    var user=register();var admin=account(Role.ADMIN);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
    try {
      var verification=pool.submit(()->{gate.await();verifyCode("000042",200);return true;});
      var roleChange=pool.submit(()->{gate.await();call(admin,patch("/admin/users/"+user.getId()+"/role"),
          Map.of("role","DATA_MANAGER","reason","Concurrent test"),200);return true;});
      gate.countDown();assertThat(verification.get(20,TimeUnit.SECONDS)).isTrue();assertThat(roleChange.get(20,TimeUnit.SECONDS)).isTrue();
      var updated=users.findById(user.getId()).orElseThrow();assertThat(updated.isEmailVerified()).isTrue();assertThat(updated.getRole()).isEqualTo(Role.DATA_MANAGER);
    } finally {pool.shutdownNow();}
  }

  @Test void verificationDatabaseFailureRollsBackConsumptionAndAccountFlag() throws Exception {
    var user=register();String suffix=UUID.randomUUID().toString().replace("-","");
    String function="reject_verify_"+suffix;String trigger="reject_verify_trigger_"+suffix;
    jdbc.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.id='"+user.getId()+"'::uuid and new.email_verified then raise exception 'Test verification failure'; end if; return new; end $$");
    jdbc.execute("create trigger "+trigger+" before update on \"user\" for each row execute function "+function+"()");
    try {
      verifyCode("000042",500);assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
      assertThat(jdbc.queryForObject("select consumed_at from email_verification_otp where user_id=?",java.sql.Timestamp.class,user.getId())).isNull();
    } finally {jdbc.execute("drop trigger "+trigger+" on \"user\"");jdbc.execute("drop function "+function+"()");}
    verifyCode("000042",200);
  }

  @Test void v13UpgradeGrandfathersExistingAccountsAndAddsVerificationStorage() {
    String schema="email_otp_"+UUID.randomUUID().toString().replace("-","");
    var old=Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("12").load();old.migrate();
    try {
      var id=UUID.randomUUID();jdbc.update("insert into "+schema+".\"user\"(id,email,full_name,role,status,created_at,updated_at) values(?,'old@example.test','Old','BRAND','ACTIVE',now(),now())",id);
      var current=Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("13").load();
      assertThat(current.migrate().migrationsExecuted).isEqualTo(1);assertThat(current.validateWithResult().validationSuccessful).isTrue();
      assertThat(jdbc.queryForObject("select email_verified from "+schema+".\"user\" where id=?",Boolean.class,id)).isTrue();
      assertThat(jdbc.queryForObject("select count(*) from "+schema+".email_verification_otp",Long.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from "+schema+".email_verification_delivery",Long.class)).isZero();
    } finally {jdbc.execute("drop schema "+schema+" cascade");}
  }
}
