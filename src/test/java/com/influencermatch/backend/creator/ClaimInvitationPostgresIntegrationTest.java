package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

@org.springframework.test.context.TestPropertySource(properties="application.auth.email-verification.secret=test-email-secret-at-least-32-characters")
class ClaimInvitationPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean VerificationEmailSender sender;
  @MockBean OAuthProofClient provider;
  @MockBean Clock clock;
  @Autowired CreatorRepository creators;
  @Autowired CreatorMasterRepository masters;
  @Autowired CreatorInvitationRepository invitations;
  @Autowired com.influencermatch.backend.creator.email.ClaimEmailJobRepository emailJobs;
  @Autowired com.influencermatch.backend.creator.email.ClaimEmailWorker emailWorker;
  Instant now=Instant.parse("2026-10-23T00:00:00Z");
  User brand,creator,manager;
  UUID campaign;
  Creator channel;
  @BeforeEach void setup() throws Exception {
    when(clock.instant()).thenReturn(now);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    brand=account(Role.BRAND);creator=account(Role.CREATOR);manager=account(Role.DATA_MANAGER);
    campaign=createCampaign(brand,createBrand(brand));
    channel=creators.saveAndFlush(Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("target").build());
    when(provider.exchangeAndVerify(anyString(),anyString(),any(),any())).thenReturn(OAuthProofClient.ProofResult.builder()
        .providerUserId(channel.getExternalId()).providerUsername(channel.getUserName()).accessToken("access-secret")
        .refreshToken("refresh-secret").tokenExpiresAt(LocalDateTime.ofInstant(now.plusSeconds(3600),ZoneOffset.UTC)).rawProfileData(Map.of()).build());
  }
  String path(){return "/creators/"+channel.getId()+"/invite";}
  Map<String,Object> body(){return Map.of("email",creator.getEmail(),"campaignId",campaign);}
  String invite() throws Exception {
    var r=call(brand,post(path()),body(),200).path("data");assertThat(r.has("rawToken")).isFalse();
    assertThat(r.path("emailStatus").asText()).isEqualTo("QUEUED");
    emailWorker.process(emailJobs.findByInvitationId(UUID.fromString(r.path("id").asText())).orElseThrow().getId());
    var link=ArgumentCaptor.forClass(String.class);verify(sender,atLeastOnce()).sendCreatorClaimInvitation(eq(creator.getEmail()),link.capture());
    assertThat(link.getValue()).startsWith("http://localhost:5173/claim-profile?token=");
    String token=java.net.URI.create(link.getValue()).getQuery().substring(6);
    assertThat(token).hasSize(64);assertThat(r.toString()).doesNotContain(token);
    return token;
  }
  void expire(String token,LocalDateTime expiry) {
    var invitation=invitations.findByTokenHash(com.influencermatch.backend.creator.service.CreatorInvitationService.hashToken(token)).orElseThrow();
    invitation.setExpiresAt(expiry);invitations.saveAndFlush(invitation);
    assertThat(invitations.findById(invitation.getId()).orElseThrow().getExpiresAt()).isEqualTo(expiry);
  }
  int callback(String token,User actor) throws Exception {
    return mvc.perform(post("/creators/claim/oauth/tiktok/callback").header("Authorization","Bearer "+jwt.generateToken(actor))
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("token",token,"code","dev-proof")))).andReturn().getResponse().getStatus();
  }
  com.fasterxml.jackson.databind.JsonNode callbackResult(String token,int expected) throws Exception {
    return call(creator,post("/creators/claim/oauth/tiktok/callback"),Map.of("token",token,"code","dev-proof"),expected);
  }
  // Full rows include owner/tick, encrypted tokens, invitation and timestamps, not just counts.
  List<List<String>> claimState() {
    var state=new ArrayList<List<String>>();
    for(String table:List.of("creator","creator_invitation","creator_account_verification","creator_provider_identity","creator_identity_audit","creator_master","audit_log")) {
      String filter=switch(table) {
        case "creator" -> "id";
        case "creator_invitation" -> "target_creator_id";
        case "creator_identity_audit" -> "account_id";
        case "creator_master" -> "user_id";
        case "audit_log" -> "entity_id";
        default -> "creator_id";
      };
      state.add(jdbc.queryForList("select to_jsonb(t)::text from "+table+" t where "+filter+"=? order by id",String.class,
          table.equals("creator_master")?creator.getId():channel.getId()));
    }
    return state;
  }
  void owner(User owner) {
    var master=masters.saveAndFlush(CreatorMaster.builder().user(owner).canonicalName("Owner").status("ACTIVE").build());
    channel.setMaster(master);channel.setSourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED);
    channel.setAuthorizedAt(LocalDateTime.ofInstant(now,ZoneOffset.UTC));creators.saveAndFlush(channel);
  }
  long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
  long rejectionAudits(){return jdbc.queryForObject("select count(*) from audit_log where entity_id=? and action='REJECT_CREATOR_INVITATION_OR_OWNERSHIP'",Long.class,channel.getId());}
  @Test void sendsRealTransportContractWithoutTokenOrRegistrationSideEffects() throws Exception {
    String token=invite();var saved=invitations.findByTokenHash(com.influencermatch.backend.creator.service.CreatorInvitationService.hashToken(token)).orElseThrow();
    assertThat(saved.getExpiresAt()).isEqualTo(LocalDateTime.ofInstant(now.plusSeconds(7*86400),ZoneOffset.UTC));
    assertThat(saved.getTokenHash()).isNotEqualTo(token);assertThat(saved.getBrand()).isNotNull();
    assertThat(jdbc.queryForObject("select master_id from creator where id=?",UUID.class,channel.getId())).isNull();
    var verified=call(null,get("/creators/claim/invitation/verify").param("token",token),null,200).path("data");
    assertThat(verified.path("creatorId").asText()).isEqualTo(channel.getId().toString());
    assertThat(verified.toString()).doesNotContain("invitedEmail","tokenHash","rawToken","email");
    assertThat(saved.getStatus()).isEqualTo("PENDING");
  }
  @Test void unknownRecipientGetsClaimInvitationButCannotRegisterFromIt() throws Exception {
    String unknown=UUID.randomUUID()+"@example.test";
    var response=call(brand,post(path()),Map.of("email",unknown),200);assertThat(users.findByEmail(unknown)).isEmpty();
    emailWorker.process(emailJobs.findByInvitationId(UUID.fromString(response.path("data").path("id").asText())).orElseThrow().getId());
    var link=ArgumentCaptor.forClass(String.class);verify(sender).sendCreatorClaimInvitation(eq(unknown),link.capture());
    String token=java.net.URI.create(link.getValue()).getQuery().substring(6);
    call(null,post("/auth/creator/register"),Map.of("invitationToken",token,"password","Password123!","fullName","No bypass"),400);
    assertThat(users.findByEmail(unknown)).isEmpty();
  }
  @ParameterizedTest @ValueSource(strings={"path","collection"})
  void ownedChannelReturns409AndDurableAudit(String endpoint) throws Exception {
    owner(creator);long before=count("creator_invitation");
    var req=endpoint.equals("path")?post(path()):post("/creators/invitations");
    var b=new HashMap<String,Object>(body());b.put("creatorId",channel.getId());
    var error=call(brand,req,b,409);assertThat(error.path("code").asText()).isEqualTo("CREATOR_ALREADY_LINKED");
    assertThat(error.toString()).doesNotContain(creator.getEmail());assertThat(count("creator_invitation")).isEqualTo(before);
    assertThat(rejectionAudits()).isEqualTo(1);verifyNoInteractions(sender);
  }
  @Test void duplicatePendingIsGlobalAndDoesNotReplaceOrSend() throws Exception {
    String token=invite();var other=account(Role.BRAND);createBrand(other);long before=count("creator_invitation");
    assertThat(call(other,post(path()),Map.of("email","other@example.test"),409).path("code").asText()).isEqualTo("CREATOR_INVITATION_PENDING");
    assertThat(count("creator_invitation")).isEqualTo(before);assertThat(rejectionAudits()).isEqualTo(1);
    call(null,get("/creators/claim/invitation/verify").param("token",token),null,200);
    verify(sender,times(1)).sendCreatorClaimInvitation(anyString(),anyString());
  }
  @ParameterizedTest @ValueSource(longs={0,-1})
  void expiredAtBoundaryDoesNotBlockNewInvitation(long seconds) throws Exception {
    String token=invite();expire(token,LocalDateTime.ofInstant(now.plusSeconds(seconds),ZoneOffset.UTC));
    call(null,get("/creators/claim/invitation/verify").param("token",token),null,409);
    var response=call(brand,post(path()),body(),200);
    emailWorker.process(emailJobs.findByInvitationId(UUID.fromString(response.path("data").path("id").asText())).orElseThrow().getId());
    verify(sender,times(2)).sendCreatorClaimInvitation(anyString(),anyString());
  }
  @ParameterizedTest @EnumSource(value=Role.class,names={"CREATOR","DATA_MANAGER","ADMIN"})
  void onlyBrandMayInvite(Role role) throws Exception {call(account(role),post(path()),body(),403);verifyNoInteractions(sender);}
  @Test void brandMustOwnProfileCampaignAndEffectivePermission() throws Exception {
    call(null,post(path()),body(),401);
    call(account(Role.BRAND),post(path()),body(),404);
    var other=account(Role.BRAND);var foreign=createCampaign(other,createBrand(other));
    call(brand,post(path()),Map.of("email",creator.getEmail(),"campaignId",foreign),404);
    call(brand,post(path()),Map.of("email",creator.getEmail(),"campaignId",UUID.randomUUID()),404);
    jdbc.update("insert into role_permission_override(id,role,permission,enabled) values(?,'BRAND','MANAGE_OWN_SHORTLIST',false)",UUID.randomUUID());
    try {call(brand,post(path()),body(),403);} finally {jdbc.update("delete from role_permission_override where role='BRAND' and permission='MANAGE_OWN_SHORTLIST'");}
    verifyNoInteractions(sender);assertThat(rejectionAudits()).isZero();
  }
  @Test void simultaneousInvitationsOnlySendOnce() throws Exception {
    var other=account(Role.BRAND);createBrand(other);var gate=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> results=new ArrayList<>();for(User actor:List.of(brand,other))results.add(pool.submit(()->{gate.await();
        return mvc.perform(post(path()).header("Authorization","Bearer "+jwt.generateToken(actor)).contentType("application/json")
          .content(json.writeValueAsBytes(Map.of("email",creator.getEmail())))).andReturn().getResponse().getStatus();}));
      gate.countDown();assertThat(List.of(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
    }
    assertThat(jdbc.queryForObject("select count(*) from creator_invitation where target_creator_id=?",Long.class,channel.getId())).isEqualTo(1);
    var invitation=invitations.findByTargetCreatorId(channel.getId()).getFirst();
    emailWorker.process(emailJobs.findByInvitationId(invitation.getId()).orElseThrow().getId());
    assertThat(rejectionAudits()).isEqualTo(1);verify(sender,times(1)).sendCreatorClaimInvitation(anyString(),anyString());
  }
  @ParameterizedTest @ValueSource(strings={"configuration","smtp"})
  void configurationFailureRollsBackButSmtpFailureAfterCommitKeepsInvitation(String failure) throws Exception {
    var error=new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unavailable");
    if(failure.equals("configuration"))doThrow(error).when(sender).requireConfigured();
    else doThrow(error).when(sender).sendCreatorClaimInvitation(anyString(),anyString());
    if(failure.equals("configuration")) {
      call(brand,post(path()),body(),503);
      assertThat(jdbc.queryForObject("select count(*) from creator_invitation where target_creator_id=?",Long.class,channel.getId())).isZero();
    } else {
      var response=call(brand,post(path()),body(),200);
      var job=emailJobs.findByInvitationId(UUID.fromString(response.path("data").path("id").asText())).orElseThrow();
      emailWorker.process(job.getId());job=emailJobs.findById(job.getId()).orElseThrow();
      assertThat(job.getStatus()).isEqualTo("QUEUED");assertThat(job.getAttempts()).isEqualTo(1);
      assertThat(job.getNextAttemptAt()).isEqualTo(LocalDateTime.ofInstant(now.plusSeconds(15),ZoneOffset.UTC));
      assertThat(invitations.findById(job.getInvitation().getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }
  }
  @Test void twoCallbacksConsumeInvitationAndCreateMasterOnce() throws Exception {
    String token=invite();var gate=new CountDownLatch(1);var ready=new CountDownLatch(2);
    try(var pool=Executors.newFixedThreadPool(2)) {
      Callable<org.springframework.mock.web.MockHttpServletResponse> request=()->{
        ready.countDown();if(!gate.await(10,TimeUnit.SECONDS))throw new AssertionError("Start gate timed out");
        return mvc.perform(post("/creators/claim/oauth/tiktok/callback").header("Authorization","Bearer "+jwt.generateToken(creator))
            .contentType("application/json").content(json.writeValueAsBytes(Map.of("token",token,"code","dev-proof")))).andReturn().getResponse();
      };
      var first=pool.submit(request);var second=pool.submit(request);
      assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();gate.countDown();
      var responses=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
      assertThat(responses).extracting(r->r.getStatus()).containsExactlyInAnyOrder(200,409);
      var rejected=responses.stream().filter(r->r.getStatus()==409).findFirst().orElseThrow();
      assertThat(json.readTree(rejected.getContentAsString()).path("code").asText()).isEqualTo("INVITATION_ALREADY_PROCESSED");
    }
    assertThat(jdbc.queryForObject("select count(*) from creator_master where user_id=?",Long.class,creator.getId())).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from creator_account_verification where creator_id=?",Long.class,channel.getId())).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from creator_identity_audit where account_id=? and event_type='CLAIM_MASTER'",Long.class,channel.getId())).isEqualTo(1);
    var beforeReplay=claimState();
    assertThat(callbackResult(token,409).path("code").asText()).isEqualTo("INVITATION_ALREADY_PROCESSED");
    assertThat(claimState()).isEqualTo(beforeReplay);
    verify(provider,times(1)).exchangeAndVerify(anyString(),anyString(),any(),any());
  }
  @Test void simultaneousDifferentChannelsReuseOneMasterForSameCreator() throws Exception {
    String firstToken=invite();UUID firstId=channel.getId();
    channel=creators.saveAndFlush(Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("second-target").build());
    String secondToken=invite();UUID secondId=channel.getId();
    when(provider.exchangeAndVerify(anyString(),anyString(),any(),any())).thenAnswer(invocation->OAuthProofClient.ProofResult.builder()
        .providerUserId(invocation.getArgument(2)).providerUsername(invocation.getArgument(3)).accessToken("access-secret")
        .refreshToken("refresh-secret").tokenExpiresAt(LocalDateTime.ofInstant(now.plusSeconds(3600),ZoneOffset.UTC)).rawProfileData(Map.of()).build());
    var ready=new CountDownLatch(2);var gate=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var tasks=new ArrayList<Future<Integer>>();
      for(String token:List.of(firstToken,secondToken))tasks.add(pool.submit(()->{
        ready.countDown();if(!gate.await(10,TimeUnit.SECONDS))throw new AssertionError("Start gate timed out");
        return callback(token,creator);
      }));
      assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();gate.countDown();
      assertThat(List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS))).containsExactly(200,200);
    }
    UUID masterId=jdbc.queryForObject("select id from creator_master where user_id=?",UUID.class,creator.getId());
    for(UUID id:List.of(firstId,secondId)) {
      assertThat(jdbc.queryForObject("select master_id from creator where id=?",UUID.class,id)).isEqualTo(masterId);
      assertThat(jdbc.queryForObject("select source_type from creator where id=?",String.class,id)).isEqualTo("AUTHORIZED");
      assertThat(jdbc.queryForObject("select status from creator_invitation where target_creator_id=?",String.class,id)).isEqualTo("ACCEPTED");
      assertThat(jdbc.queryForObject("select count(*) from creator_account_verification where creator_id=?",Long.class,id)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from creator_identity_audit where account_id=? and event_type='CLAIM_MASTER'",Long.class,id)).isEqualTo(1);
    }
    verify(provider,times(2)).exchangeAndVerify(anyString(),anyString(),any(),any());
  }
  @ParameterizedTest @ValueSource(longs={-1,0})
  void expiredCallbackPreservesAllClaimData(long seconds) throws Exception {
    String token=invite();expire(token,LocalDateTime.ofInstant(now.plusSeconds(seconds),ZoneOffset.UTC));
    var before=claimState();
    assertThat(callbackResult(token,409).path("code").asText()).isEqualTo("INVITATION_EXPIRED");
    assertThat(claimState()).isEqualTo(before);verifyNoInteractions(provider);
  }
  @Test void usedInvitationPreservesAllClaimDataWithoutReachingProvider() throws Exception {
    String token=invite();
    jdbc.update("update creator_invitation set status='ACCEPTED' where target_creator_id=?",channel.getId());
    var before=claimState();
    assertThat(callbackResult(token,409).path("code").asText()).isEqualTo("INVITATION_ALREADY_PROCESSED");
    assertThat(claimState()).isEqualTo(before);verifyNoInteractions(provider);
  }
  @Test void wrongEmailExpiredAndUsedInvitationNeverReachProvider() throws Exception {
    String token=invite();assertThat(callback(token,account(Role.CREATOR))).isEqualTo(403);
    expire(token,LocalDateTime.ofInstant(now,ZoneOffset.UTC));
    assertThat(callback(token,creator)).isEqualTo(409);
    jdbc.update("update creator_invitation set status='ACCEPTED' where target_creator_id=?",channel.getId());
    assertThat(callback(token,creator)).isEqualTo(409);verifyNoInteractions(provider);
    assertThat(jdbc.queryForObject("select master_id from creator where id=?",UUID.class,channel.getId())).isNull();
  }
  @Test void ownershipAppearingAfterInviteBlocksCallbackAndManualClaimWithAudit() throws Exception {
    String token=invite();owner(account(Role.CREATOR));long verifications=count("creator_account_verification");
    assertThat(callback(token,creator)).isEqualTo(409);
    call(creator,post("/creators/"+channel.getId()+"/claim"),Map.of("evidenceUrls",List.of("https://example.test/proof"),"note","Evidence"),409);
    assertThat(rejectionAudits()).isEqualTo(2);assertThat(count("creator_account_verification")).isEqualTo(verifications);
    assertThat(jdbc.queryForObject("select status from creator_invitation where target_creator_id=?",String.class,channel.getId())).isEqualTo("PENDING");verifyNoInteractions(provider);
  }
  @Test void linkingOtherOwnersChannelReturns409WithoutConflictQueueOrTokens() throws Exception {
    var owner=account(Role.CREATOR);owner(owner);
    masters.saveAndFlush(CreatorMaster.builder().user(creator).canonicalName("Requester").build());
    long before=count("creator_identity_conflict"),tokens=count("creator_account_verification");
    call(creator,post("/creators/portal/accounts/connect/tiktok/callback"),Map.of("code","dev-proof"),409);
    assertThat(count("creator_identity_conflict")).isEqualTo(before);assertThat(count("creator_account_verification")).isEqualTo(tokens);
    assertThat(rejectionAudits()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select m.user_id from creator c join creator_master m on m.id=c.master_id where c.id=?",UUID.class,channel.getId())).isEqualTo(owner.getId());
  }
  @Test void invitationEmailIsEnforcedForManualEvidenceAndReviewConsumesMatchingInvitation() throws Exception {
    invite();var evidence=Map.of("evidenceUrls",List.of("https://proof.example.test"),"note","My channel");
    var other=account(Role.CREATOR);
    call(other,post("/creators/"+channel.getId()+"/claim"),evidence,403);
    var claim=call(creator,post("/creators/"+channel.getId()+"/claim"),evidence,201).path("data");
    assertThat(claim.path("status").asText()).isEqualTo("PENDING");
    call(manager,patch("/data/creator-claims/"+claim.path("id").asText()+"/review"),Map.of("decision","APPROVED","reason","Evidence checked"),200);
    assertThat(jdbc.queryForObject("select status from creator_invitation where target_creator_id=?",String.class,channel.getId())).isEqualTo("ACCEPTED");
    call(manager,post("/data/creator-claims/"+claim.path("id").asText()+"/revoke"),Map.of("reason","Withdrawn"),200);
    assertThat(jdbc.queryForObject("select source_type from creator where id=?",String.class,channel.getId())).isEqualTo("PUBLIC");
  }
  @Test void repeatedSameOwnerLinkRetainsOwnershipAndDoesNotCreateConflict() throws Exception {
    owner(creator);long before=count("creator_identity_conflict");
    for(int i=0;i<2;i++) {
      var r=call(creator,post("/creators/portal/accounts/connect/tiktok/callback"),Map.of("code","dev-proof"),200).path("data");
      assertThat(r.path("status").asText()).isEqualTo("ALREADY_LINKED");
    }
    assertThat(count("creator_identity_conflict")).isEqualTo(before);assertThat(rejectionAudits()).isZero();
    assertThat(jdbc.queryForObject("select m.user_id from creator c join creator_master m on m.id=c.master_id where c.id=?",UUID.class,channel.getId())).isEqualTo(creator.getId());
  }
  @Test void callbackAuditFailureRollsBackInvitationOwnerVerificationAndMaster() throws Exception {
    String token=invite(),suffix=UUID.randomUUID().toString().replace("-","");String fn="fail_claim_"+suffix,tr="fail_claim_tr_"+suffix;
    var before=claimState();
    jdbc.execute("create function "+fn+"() returns trigger language plpgsql as $$ begin if new.account_id='"+channel.getId()+"'::uuid then raise exception 'Claim audit failure'; end if; return new; end $$");
    jdbc.execute("create trigger "+tr+" before insert on creator_identity_audit for each row execute function "+fn+"()");
    try {
      assertThat(callback(token,creator)).isEqualTo(500);
      assertThat(claimState()).isEqualTo(before);
      assertThat(jdbc.queryForObject("select master_id from creator where id=?",UUID.class,channel.getId())).isNull();
      assertThat(jdbc.queryForObject("select count(*) from creator_master where user_id=?",Long.class,creator.getId())).isZero();
      assertThat(jdbc.queryForObject("select count(*) from creator_account_verification where creator_id=?",Long.class,channel.getId())).isZero();
      assertThat(jdbc.queryForObject("select status from creator_invitation where target_creator_id=?",String.class,channel.getId())).isEqualTo("PENDING");
    } finally {jdbc.execute("drop trigger "+tr+" on creator_identity_audit");jdbc.execute("drop function "+fn+"()");}
    assertThat(callback(token,creator)).isEqualTo(200);
  }
  @Test void invitationTokenIsNotWrittenToApplicationLogs() throws Exception {
    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(com.influencermatch.backend.creator.service.CreatorInvitationService.class);
    var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
    try {String token=invite();assertThat(appender.list).allSatisfy(event->assertThat(event.getFormattedMessage()).doesNotContain(token,"claim-profile?token="));}
    finally {logger.detachAppender(appender);appender.stop();}
  }
  @ParameterizedTest @EnumSource(Role.class)
  void retiredTransferEndpointIs404ForAuthenticatedAccounts(Role role) throws Exception {
    call(account(role),post("/admin/creator-conflicts/"+UUID.randomUUID()+"/resolve"),Map.of("decision","APPROVE_TRANSFER"),404);
  }
  @Test void auditFailureRollsBackRejectionWithoutChangingBusinessData() throws Exception {
    owner(creator);String suffix=UUID.randomUUID().toString().replace("-","");String fn="reject_conflict_"+suffix,tr="reject_conflict_tr_"+suffix;
    jdbc.execute("create function "+fn+"() returns trigger language plpgsql as $$ begin if new.entity_id='"+channel.getId()+"'::uuid then raise exception 'Audit fixture failure'; end if; return new; end $$");
    jdbc.execute("create trigger "+tr+" before insert on audit_log for each row execute function "+fn+"()");
    try {call(brand,post(path()),body(),500);assertThat(rejectionAudits()).isZero();verifyNoInteractions(sender);}
    finally {jdbc.execute("drop trigger "+tr+" on audit_log");jdbc.execute("drop function "+fn+"()");}
    call(brand,post(path()),body(),409);assertThat(rejectionAudits()).isEqualTo(1);
  }
}
