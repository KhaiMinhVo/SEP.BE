package com.influencermatch.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
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

@TestPropertySource(properties={"application.auth.email-verification.secret=test-email-otp-secret-at-least-32-characters",
    "application.auth.creator-invitation.frontend-base-url=http://localhost:5173"})
class CreatorRegistrationPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean VerificationEmailSender sender;
  @MockBean Clock clock;
  @SpyBean EmailOtpCodes crypto;
  @Autowired ExternalIdentityRepository identities;
  @Autowired RefreshTokenService refresh;
  @Autowired CreatorRepository creators;
  Instant now=Instant.parse("2026-10-22T00:00:00Z");
  String email;
  @BeforeEach void setup() {
    email=UUID.randomUUID()+"@invitations.test";when(clock.instant()).thenReturn(now);doReturn("000042").when(crypto).generate();
    jdbc.update("delete from role_permission_override where permission='INVITE_CREATOR'");
  }
  String invite() throws Exception {return invite(account(Role.DATA_MANAGER));}
  String invite(User actor) throws Exception {
    var response=call(actor,post("/data/creator-invitations"),Map.of("email",email),201);
    var link=ArgumentCaptor.forClass(String.class);verify(sender,atLeastOnce()).sendCreatorInvitation(eq(email),link.capture());
    String token=java.net.URI.create(link.getValue()).getQuery().substring("token=".length());
    assertThat(response.toString()).doesNotContain(token,"tokenHash");
    assertThat(response.path("data").path("expiresAt").asText()).isEqualTo(now.plusSeconds(172800).toString());return token;
  }
  Map<String,Object> creatorBody(String token) {return Map.of("invitationToken",token,"password","Password123!","fullName","  Creator  ");}
  void register(String token,int status) throws Exception {call(null,post("/auth/creator/register"),creatorBody(token),status);}
  void verifyEmail(int status) throws Exception {call(null,post("/auth/verify-email"),Map.of("email",email,"otp","000042"),status);}
  void resolve(String token,int status) throws Exception {call(null,post("/auth/creator-invitations/resolve"),Map.of("token",token),status);}
  User legacy(Role role) {
    var user=account(role);user.setPasswordHash(null);users.saveAndFlush(user);
    identities.saveAndFlush(ExternalIdentity.builder().user(user).provider(ExternalIdentityProvider.GOOGLE)
        .providerSubject(UUID.randomUUID().toString()).providerEmail(user.getEmail()).emailVerified(true).build());email=user.getEmail();return user;
  }
  void requestPassword(int status) throws Exception {call(null,post("/auth/password-setup/request"),Map.of("email",email),status);}
  void confirm(String otp,int status) throws Exception {call(null,post("/auth/password-setup/confirm"),Map.of("email",email,"otp",otp,"newPassword","NewPassword123!"),status);}

  @Test void creatorInvitationRegistrationOtpLoginAndClaimCompleteWithoutGoogle() throws Exception {
    String token=invite();assertThat(users.findByEmail(email)).isEmpty();resolve(token,200);resolve(token,200);
    String hash=jdbc.queryForObject("select token_hash from creator_invitation where email=?",String.class,email);
    assertThat(hash).hasSize(64).isNotEqualTo(token);
    assertThat(jdbc.queryForObject("select new_value::text from audit_log where entity_id=(select id from creator_invitation where email=?)",String.class,email)).doesNotContain(token);
    register(token,201);resolve(token,400);register(token,400);
    var user=users.findByEmail(email).orElseThrow();assertThat(user.getRole()).isEqualTo(Role.CREATOR);assertThat(user.getFullName()).isEqualTo("Creator");
    assertThat(user.isEmailVerified()).isFalse();assertThat(encoder.matches("Password123!",user.getPasswordHash())).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from creator_master where user_id=?",Long.class,user.getId())).isEqualTo(1);
    call(null,post("/auth/login"),Map.of("email",email,"password","Password123!"),403);
    var channel=creators.saveAndFlush(Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("Creator").build());
    call(user,post("/creators/"+channel.getId()+"/claim"),Map.of("evidenceUrls",List.of("https://example.test/proof"),"note","Proof"),401);
    assertThat(creators.findById(channel.getId()).orElseThrow().getOwner()).isNull();verifyEmail(200);login(user);
    assertThat(identities.findByUserIdAndProvider(user.getId(),ExternalIdentityProvider.GOOGLE)).isEmpty();
    var claim=call(user,post("/creators/"+channel.getId()+"/claim"),Map.of("evidenceUrls",List.of("https://example.test/proof"),"note","Proof"),201).path("data");
    assertThat(claim.path("status").asText()).isEqualTo("PENDING");assertThat(creators.findById(channel.getId()).orElseThrow().getOwner()).isNull();
    call(account(Role.ADMIN),patch("/data/creator-claims/"+claim.path("id").asText()+"/review"),Map.of("decision","APPROVED","reason","Verified manually"),200);
    assertThat(creators.findById(channel.getId()).orElseThrow().getOwner().getId()).isEqualTo(user.getId());
  }
  @ParameterizedTest @EnumSource(value=Role.class,names={"ADMIN","DATA_MANAGER"})
  void adminsAndDataManagersCanInviteWithoutTokensInResponse(Role role) throws Exception {invite(account(role));}
  @ParameterizedTest @EnumSource(value=Role.class,names={"BRAND","CREATOR"})
  void otherRolesCannotInvite(Role role) throws Exception {
    call(account(role),post("/data/creator-invitations"),Map.of("email",email),403);verifyNoInteractions(sender);
  }
  @Test void anonymousAndDynamicallyDisabledInviterAreDenied() throws Exception {
    call(null,post("/data/creator-invitations"),Map.of("email",email),401);
    var admin=account(Role.ADMIN);var manager=account(Role.DATA_MANAGER);String access=jwt.generateToken(manager);
    long version=jdbc.queryForObject("select version from role_permission_config where role='DATA_MANAGER'",Long.class);
    call(admin,patch("/admin/role-permissions/DATA_MANAGER"),Map.of("version",version,"changes",List.of(Map.of("permission","INVITE_CREATOR","enabled",false))),200);
    callToken(access,post("/data/creator-invitations"),Map.of("email",email),403);verifyNoInteractions(sender);
    call(admin,patch("/admin/role-permissions/DATA_MANAGER"),Map.of("version",version+1,"changes",List.of(Map.of("permission","INVITE_CREATOR","enabled",true))),200);
    assertThat(callToken(access,post("/data/creator-invitations"),Map.of("email",email),201).path("success").asBoolean()).isTrue();
  }
  @ParameterizedTest @ValueSource(strings={"ADMIN","DATA_MANAGER","CREATOR","BRAND",""}) @NullSource
  void publicRegistrationRejectsAnyRoleProperty(String role) throws Exception {
    var body=new HashMap<String,Object>();body.put("email",email);body.put("password","Password123!");body.put("fullName","Brand");body.put("role",role);
    if(role==null) {
      mvc.perform(post("/auth/register").contentType("application/json")
          .content("{\"email\":\""+email+"\",\"password\":\"Password123!\",\"fullName\":\"Brand\",\"role\":null}"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    } else call(null,post("/auth/register"),body,400);
    assertThat(users.findByEmail(email)).isEmpty();verifyNoInteractions(sender);
  }
  @Test void publicRegistrationAlwaysCreatesUnverifiedBrand() throws Exception {
    call(null,post("/auth/register"),Map.of("email",email,"password","Password123!","fullName","Brand"),201);
    var user=users.findByEmail(email).orElseThrow();assertThat(user.getRole()).isEqualTo(Role.BRAND);assertThat(user.isEmailVerified()).isFalse();
    verifyEmail(200);login(user);
  }
  @ParameterizedTest @ValueSource(strings={"email","role"})
  void creatorRegistrationRejectsClientEmailOrRole(String field) throws Exception {
    String token=invite();var body=new HashMap<>(creatorBody(token));body.put(field,field.equals("email")?"other@example.test":"ADMIN");
    call(null,post("/auth/creator/register"),body,400);assertThat(users.findByEmail(email)).isEmpty();resolve(token,200);
  }
  @ParameterizedTest @ValueSource(strings={"missing-token","missing-password","short-password","blank-name"})
  void invalidCreatorRegistrationNeverConsumesInvitation(String invalid) throws Exception {
    String token=invite();var body=new HashMap<>(creatorBody(token));
    if(invalid.equals("missing-token"))body.remove("invitationToken");if(invalid.equals("missing-password"))body.remove("password");
    if(invalid.equals("short-password"))body.put("password","short");if(invalid.equals("blank-name"))body.put("fullName","  ");
    call(null,post("/auth/creator/register"),body,400);assertThat(users.findByEmail(email)).isEmpty();resolve(token,200);verify(sender,never()).send(eq(email),anyString());
  }
  @ParameterizedTest @ValueSource(strings={"","not-an-email","   "})
  void invalidInvitationEmailNeverSends(String invalid) throws Exception {
    call(account(Role.ADMIN),post("/data/creator-invitations"),Map.of("email",invalid),400);verifyNoInteractions(sender);
  }
  @Test void invalidExpiredAndReplacedInvitationCannotRegister() throws Exception {
    resolve("unknown",400);register("unknown",400);String old=invite();
    var manager=account(Role.DATA_MANAGER);when(clock.instant()).thenReturn(now.plusSeconds(59));
    call(manager,post("/data/creator-invitations"),Map.of("email",email),429);resolve(old,200);
    now=now.plusSeconds(60);when(clock.instant()).thenReturn(now);String replacement=invite(manager);assertThat(replacement).isNotEqualTo(old);
    resolve(old,400);register(old,400);when(clock.instant()).thenReturn(now.plusSeconds(172800));resolve(replacement,400);register(replacement,400);
  }
  @Test void invitationSendBudgetIsFivePerRollingHour() throws Exception {
    var manager=account(Role.DATA_MANAGER);invite(manager);
    for(int i=1;i<5;i++){when(clock.instant()).thenReturn(now.plusSeconds(i*60));call(manager,post("/data/creator-invitations"),Map.of("email",email),201);}
    when(clock.instant()).thenReturn(now.plusSeconds(300));call(manager,post("/data/creator-invitations"),Map.of("email",email),429);
    when(clock.instant()).thenReturn(now.plusSeconds(3601));call(manager,post("/data/creator-invitations"),Map.of("email",email),201);
    verify(sender,times(6)).sendCreatorInvitation(eq(email),anyString());
  }
  @Test void existingBrandCannotBeInvitedOrConverted() throws Exception {
    String token=invite();call(null,post("/auth/register"),Map.of("email",email,"password","Password123!","fullName","Brand"),201);
    register(token,409);call(account(Role.ADMIN),post("/data/creator-invitations"),Map.of("email",email),409);
    assertThat(users.findByEmail(email).orElseThrow().getRole()).isEqualTo(Role.BRAND);
  }
  @Test void invitationSmtpFailureRollsBackTokenDeliveryAndAudit() throws Exception {
    var admin=account(Role.ADMIN);long audits=jdbc.queryForObject("select count(*) from audit_log",Long.class);
    doThrow(new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unavailable")).when(sender).sendCreatorInvitation(eq(email),anyString());
    call(admin,post("/data/creator-invitations"),Map.of("email",email),503);
    assertThat(jdbc.queryForObject("select count(*) from creator_invitation where email=?",Long.class,email)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from creator_invitation_delivery where email=?",Long.class,email)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from audit_log",Long.class)).isEqualTo(audits);
  }
  @Test void failedInvitationReplacementPreservesPreviousLinkAndBudget() throws Exception {
    String token=invite();var before=jdbc.queryForMap("select * from creator_invitation where email=?",email);
    when(clock.instant()).thenReturn(now.plusSeconds(60));doThrow(new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unavailable")).when(sender).sendCreatorInvitation(eq(email),anyString());
    call(account(Role.ADMIN),post("/data/creator-invitations"),Map.of("email",email),503);
    assertThat(jdbc.queryForMap("select * from creator_invitation where email=?",email)).isEqualTo(before);resolve(token,200);
    assertThat(jdbc.queryForObject("select count(*) from creator_invitation_delivery where email=?",Long.class,email)).isEqualTo(1);
  }
  @Test void auditFailureRollsBackInvitationAndDelivery() throws Exception {
    String suffix=UUID.randomUUID().toString().replace("-","");String function="reject_invite_"+suffix;String trigger="reject_invite_trigger_"+suffix;
    jdbc.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.action='INVITE_CREATOR' then raise exception 'Test audit failure'; end if; return new; end $$");
    jdbc.execute("create trigger "+trigger+" before insert on audit_log for each row execute function "+function+"()");
    try {
      call(account(Role.ADMIN),post("/data/creator-invitations"),Map.of("email",email),500);
      assertThat(jdbc.queryForObject("select count(*) from creator_invitation where email=?",Long.class,email)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from creator_invitation_delivery where email=?",Long.class,email)).isZero();
    } finally {jdbc.execute("drop trigger "+trigger+" on audit_log");jdbc.execute("drop function "+function+"()");}
  }
  @Test void registrationSmtpFailureLeavesInvitationUsableAndNoAccount() throws Exception {
    String token=invite();doThrow(new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unavailable")).when(sender).send(email,"000042");
    register(token,503);assertThat(users.findByEmail(email)).isEmpty();resolve(token,200);
    doNothing().when(sender).send(email,"000042");register(token,201);
  }
  @Test void concurrentRegistrationConsumesInvitationOnce() throws Exception {
    String token=invite();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
    try {
      Callable<Integer> task=()->{gate.await();return mvc.perform(post("/auth/creator/register").contentType("application/json").content(json.writeValueAsBytes(creatorBody(token)))).andReturn().getResponse().getStatus();};
      var first=pool.submit(task);var second=pool.submit(task);gate.countDown();assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,400);
      assertThat(jdbc.queryForObject("select count(*) from \"user\" where email=?",Long.class,email)).isEqualTo(1);verify(sender,times(1)).send(email,"000042");
    } finally {pool.shutdownNow();}
  }
  @ParameterizedTest @ValueSource(strings={"/auth/google","/auth/google/creator","/auth/google/exchange","/oauth2/authorization/google","/login/oauth2/code/google"})
  void removedGoogleEndpointsAre404ForAuthenticatedUsers(String path) throws Exception {
    var actor=account(Role.BRAND);if(path.endsWith("exchange"))call(actor,post(path),Map.of("code","old"),404);else call(actor,get(path),null,404);
  }
  @ParameterizedTest @EnumSource(value=Role.class,names={"BRAND","CREATOR"})
  void legacyGoogleAccountSetsPasswordAndRevokesOldSessions(Role role) throws Exception {
    var user=legacy(role);var session=refresh.issue(user);String oldAccess=jwt.generateToken(user);long version=user.getAuthVersion();
    requestPassword(200);verify(sender).sendPasswordSetup(email,"000042");confirm("000042",200);
    var updated=users.findById(user.getId()).orElseThrow();assertThat(updated.getRole()).isEqualTo(role);assertThat(updated.getAuthVersion()).isEqualTo(version+1);
    assertThat(encoder.matches("NewPassword123!",updated.getPasswordHash())).isTrue();assertThat(updated.isEmailVerified()).isTrue();
    assertThat(jdbc.queryForObject("select revoked_at from refresh_token where user_id=?",java.sql.Timestamp.class,user.getId())).isNotNull();
    callToken(oldAccess,get("/auth/me"),null,401);confirm("000042",400);
    call(null,post("/auth/login"),Map.of("email",email,"password","NewPassword123!"),200);
  }
  @ParameterizedTest @ValueSource(strings={"unknown","password","unverified","locked","disabled","admin","data-manager","no-identity"})
  void ineligiblePasswordSetupIsGenericAndDoesNotSend(String state) throws Exception {
    if(!state.equals("unknown")) {
      var user=legacy(state.equals("admin")?Role.ADMIN:state.equals("data-manager")?Role.DATA_MANAGER:Role.BRAND);
      if(state.equals("password"))user.setPasswordHash(encoder.encode("Existing123!"));if(state.equals("unverified"))user.setEmailVerified(false);
      if(state.equals("locked"))user.setStatus(UserStatus.LOCKED);if(state.equals("disabled"))user.setStatus(UserStatus.DISABLED);users.saveAndFlush(user);
      if(state.equals("no-identity"))jdbc.update("delete from external_identity where user_id=?",user.getId());
    }
    requestPassword(200);verify(sender,never()).sendPasswordSetup(anyString(),anyString());confirm("000042",400);
  }
  @Test void passwordWrongAttemptsPersistAndResendResetsWithNewPurposeBoundCode() throws Exception {
    var user=legacy(Role.CREATOR);requestPassword(200);
    for(int i=1;i<=5;i++){confirm("999999",i==5?429:400);assertThat(jdbc.queryForObject("select attempts from password_setup_otp where user_id=?",Integer.class,user.getId())).isEqualTo(i);}
    confirm("000042",429);requestPassword(429);when(clock.instant()).thenReturn(now.plusSeconds(60));doReturn("000043").when(crypto).generate();requestPassword(200);
    confirm("000042",400);confirm("000043",200);
  }
  @Test void passwordCodeCannotBeUsedForRegistrationAndExpiresAtTenMinutes() throws Exception {
    var user=legacy(Role.BRAND);requestPassword(200);verifyEmail(409);
    String stored=jdbc.queryForObject("select code_hash from password_setup_otp where user_id=?",String.class,user.getId());
    assertThat(crypto.matches(user.getId(),"000042",stored)).isFalse();when(clock.instant()).thenReturn(now.plusSeconds(600));confirm("000042",400);
    assertThat(users.findById(user.getId()).orElseThrow().getPasswordHash()).isNull();
  }
  @Test void passwordSetupSharesHourlyDeliveryBudget() throws Exception {
    var user=legacy(Role.BRAND);for(int i=0;i<5;i++)jdbc.update("insert into email_verification_delivery(id,user_id,sent_at) values(?,?,?)",UUID.randomUUID(),user.getId(),java.sql.Timestamp.from(now.minusSeconds(120)));
    requestPassword(429);when(clock.instant()).thenReturn(now.plusSeconds(3601));requestPassword(200);
  }
  @Test void passwordSmtpFailurePreservesOldCodeAndBudget() throws Exception {
    var user=legacy(Role.BRAND);requestPassword(200);var before=jdbc.queryForMap("select * from password_setup_otp where user_id=?",user.getId());
    when(clock.instant()).thenReturn(now.plusSeconds(60));doReturn("000043").when(crypto).generate();
    doThrow(new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unavailable")).when(sender).sendPasswordSetup(email,"000043");
    requestPassword(503);assertThat(jdbc.queryForMap("select * from password_setup_otp where user_id=?",user.getId())).isEqualTo(before);
    assertThat(jdbc.queryForObject("select count(*) from email_verification_delivery where user_id=?",Long.class,user.getId())).isEqualTo(1);confirm("000042",200);
  }
  @Test void passwordDatabaseFailureRollsBackPasswordVersionCodeAndRevocation() throws Exception {
    var user=legacy(Role.BRAND);refresh.issue(user);requestPassword(200);
    String suffix=UUID.randomUUID().toString().replace("-","");String function="reject_password_"+suffix;String trigger="reject_password_trigger_"+suffix;
    jdbc.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.id='"+user.getId()+"'::uuid and new.password_hash is not null then raise exception 'Test password failure'; end if; return new; end $$");
    jdbc.execute("create trigger "+trigger+" before update on \"user\" for each row execute function "+function+"()");
    try {
      confirm("000042",500);var unchanged=users.findById(user.getId()).orElseThrow();assertThat(unchanged.getPasswordHash()).isNull();assertThat(unchanged.getAuthVersion()).isEqualTo(user.getAuthVersion());
      assertThat(jdbc.queryForObject("select consumed_at from password_setup_otp where user_id=?",java.sql.Timestamp.class,user.getId())).isNull();
      assertThat(jdbc.queryForObject("select revoked_at from refresh_token where user_id=?",java.sql.Timestamp.class,user.getId())).isNull();
    } finally {jdbc.execute("drop trigger "+trigger+" on \"user\"");jdbc.execute("drop function "+function+"()");}
    confirm("000042",200);
  }
  @Test void concurrentPasswordConfirmationOnlySucceedsOnce() throws Exception {
    legacy(Role.CREATOR);requestPassword(200);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
    try {
      Callable<Integer> task=()->{gate.await();return mvc.perform(post("/auth/password-setup/confirm").contentType("application/json").content(json.writeValueAsBytes(Map.of("email",email,"otp","000042","newPassword","NewPassword123!")))).andReturn().getResponse().getStatus();};
      var first=pool.submit(task);var second=pool.submit(task);gate.countDown();assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,400);
    } finally {pool.shutdownNow();}
  }
  @Test void v14UpgradePreservesAccountsIdentitiesOwnershipAndExistingOtp() {
    String schema="creator_invites_"+UUID.randomUUID().toString().replace("-","");
    Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("13").load().migrate();
    var id=UUID.randomUUID();var identity=UUID.randomUUID();var channel=UUID.randomUUID();var exchange=UUID.randomUUID();
    try {
      jdbc.update("insert into "+schema+".\"user\"(id,email,full_name,role,status,created_at,updated_at) values(?,'legacy@example.test','Legacy','CREATOR','ACTIVE',now(),now())",id);
      jdbc.update("insert into "+schema+".external_identity(id,user_id,provider,provider_subject,provider_email,email_verified,created_at,updated_at) values(?,?,'GOOGLE','legacy','legacy@example.test',true,now(),now())",identity,id);
      jdbc.update("insert into "+schema+".creator(id,platform,external_id,user_name,owner_user_id,source_type,authorized_at,created_at,updated_at) values(?,'TIKTOK','legacy','legacy',?,'AUTHORIZED',now(),now(),now())",channel,id);
      jdbc.update("insert into "+schema+".email_verification_otp(user_id,code_hash,expires_at,last_sent_at) values(?,repeat('a',64),now()+interval '10 minutes',now())",id);
      jdbc.update("insert into "+schema+".auth_exchange_code(id,user_id,code_hash,expires_at,consumed_at,created_at) values(?,?,repeat('b',64),now()+interval '1 minute',now(),now())",exchange,id);
      var current=Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("14").load();assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
      assertThat(current.validateWithResult().validationSuccessful).isTrue();
      assertThat(jdbc.queryForObject("select email_verified from "+schema+".\"user\" where id=?",Boolean.class,id)).isTrue();
      assertThat(jdbc.queryForObject("select owner_user_id from "+schema+".creator where id=?",UUID.class,channel)).isEqualTo(id);
      assertThat(jdbc.queryForObject("select provider from "+schema+".external_identity where id=?",String.class,identity)).isEqualTo("GOOGLE");
      assertThat(jdbc.queryForObject("select count(*) from "+schema+".email_verification_otp",Long.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select code_hash from "+schema+".auth_exchange_code where id=?",String.class,exchange)).isEqualTo("b".repeat(64));
      for(String table:List.of("creator_invitation","creator_invitation_delivery","password_setup_otp"))assertThat(jdbc.queryForObject("select count(*) from "+schema+"."+table,Long.class)).isZero();
    } finally {jdbc.execute("drop schema "+schema+" cascade");}
  }
}
