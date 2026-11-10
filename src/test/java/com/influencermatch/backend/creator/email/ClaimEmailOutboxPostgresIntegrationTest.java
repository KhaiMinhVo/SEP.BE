package com.influencermatch.backend.creator.email;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.enums.Role;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@TestPropertySource(properties="application.auth.email-verification.secret=test-email-secret-at-least-32-characters")
class ClaimEmailOutboxPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean VerificationEmailSender sender;
  @MockBean Clock clock;
  @Autowired ClaimEmailOutboxService outbox;
  @Autowired ClaimEmailWorker worker;
  @Autowired ClaimEmailJobRepository jobs;
  @Autowired ClaimEmailCipher cipher;
  @Autowired CreatorRepository creators;
  @Autowired CreatorInvitationRepository invitations;
  @Autowired CreatorMasterRepository masters;
  @Autowired PlatformTransactionManager transactions;
  Instant instant;
  User brand,creator;
  Creator channel;
  UUID invitationId,jobId;
  @BeforeEach void setup() throws Exception {
    instant=Instant.parse("2026-10-23T00:00:00Z");
    when(clock.instant()).thenAnswer(i->instant);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    brand=account(Role.BRAND);creator=account(Role.CREATOR);createBrand(brand);
    channel=creators.saveAndFlush(Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("outbox-target").build());
  }
  LocalDateTime now(){return LocalDateTime.ofInstant(instant,ZoneOffset.UTC);}
  void advance(long seconds){instant=instant.plusSeconds(seconds);}
  void queue() throws Exception {
    var data=call(brand,post("/creators/"+channel.getId()+"/invite"),Map.of("email",creator.getEmail()),200).path("data");
    assertThat(data.path("emailStatus").asText()).isEqualTo("QUEUED");
    invitationId=UUID.fromString(data.path("id").asText());jobId=jobs.findByInvitationId(invitationId).orElseThrow().getId();
    verify(sender,never()).sendCreatorClaimInvitation(anyString(),anyString());
  }
  ClaimEmailJob job(){return jobs.findById(jobId).orElseThrow();}
  String path(String action){return "/creators/invitations/"+invitationId+"/"+action;}
  void failCycle() {
    doThrow(new org.springframework.mail.MailSendException("sensitive provider detail")).when(sender).sendCreatorClaimInvitation(anyString(),anyString());
    for(int delay:List.of(0,15,30,60,120)){advance(delay);worker.process(jobId);}
    assertThat(job().getStatus()).isEqualTo("FAILED");
  }
  @Test void queuedJobHasOnlyCiphertextAndSendsWithoutTransactionThenClearsPayload() throws Exception {
    queue();var before=job();String encrypted=before.getPayloadCiphertext();
    assertThat(outbox.due()).contains(jobId);
    assertThat(encrypted).doesNotContain(creator.getEmail(),"claim-profile","token=");
    var payload=cipher.decrypt(encrypted);
    assertThat(payload.email()).isEqualTo(creator.getEmail());
    assertThat(com.influencermatch.backend.creator.service.CreatorInvitationService.hashToken(java.net.URI.create(payload.link()).getQuery().substring(6)))
        .isEqualTo(invitations.findById(invitationId).orElseThrow().getTokenHash());
    doAnswer(i->{
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(invitations.existsById(invitationId)).isTrue();return null;
    }).when(sender).sendCreatorClaimInvitation(anyString(),anyString());
    worker.process(jobId);worker.process(jobId);
    assertThat(job().getStatus()).isEqualTo("SENT");assertThat(job().getPayloadCiphertext()).isNull();
    assertThat(job().getLastSentAt()).isEqualTo(now());assertThat(job().getAttempts()).isEqualTo(1);
    verify(sender,times(1)).sendCreatorClaimInvitation(payload.email(),payload.link());
    var response=call(brand,get(path("email-delivery")),null,200).path("data");
    assertThat(response.path("status").asText()).isEqualTo("SENT");assertThat(response.toString()).doesNotContain("token","ciphertext",payload.link());
  }
  @Test void failureBackoffAndManualRetryPreserveTokenAndExpiry() throws Exception {
    queue();var original=invitations.findById(invitationId).orElseThrow();String payload=job().getPayloadCiphertext();
    doThrow(new org.springframework.mail.MailSendException("timeout")).when(sender).sendCreatorClaimInvitation(anyString(),anyString());
    int attempts=0;
    for(int delay:List.of(15,30,60,120)) {
      worker.process(jobId);attempts++;
      assertThat(job().getAttempts()).isEqualTo(attempts);assertThat(job().getNextAttemptAt()).isEqualTo(now().plusSeconds(delay));
      worker.process(jobId);assertThat(job().getAttempts()).isEqualTo(attempts);advance(delay);
    }
    worker.process(jobId);assertThat(job().getStatus()).isEqualTo("FAILED");assertThat(job().getPayloadCiphertext()).isEqualTo(payload);
    assertThat(invitations.findById(invitationId).orElseThrow().getStatus()).isEqualTo("PENDING");
    assertThat(call(brand,post("/creators/"+channel.getId()+"/invite"),Map.of("email",creator.getEmail()),409).path("code").asText()).isEqualTo("CREATOR_INVITATION_PENDING");
    assertThat(invitations.findByTargetCreatorId(channel.getId())).hasSize(1);
    call(brand,post(path("retry-email")),null,429);advance(60);
    assertThat(call(brand,get(path("email-delivery")),null,200).path("data").path("canRetry").asBoolean()).isTrue();
    call(brand,post(path("retry-email")),null,200);call(brand,post(path("retry-email")),null,409);
    var preserved=invitations.findById(invitationId).orElseThrow();
    assertThat(preserved.getTokenHash()).isEqualTo(original.getTokenHash());assertThat(preserved.getExpiresAt()).isEqualTo(original.getExpiresAt());
    assertThat(job().getPayloadCiphertext()).isEqualTo(payload);assertThat(job().getAttempts()).isEqualTo(5);
    doNothing().when(sender).sendCreatorClaimInvitation(anyString(),anyString());worker.process(jobId);
    assertThat(job().getStatus()).isEqualTo("SENT");assertThat(job().getAttempts()).isEqualTo(6);
  }
  @Test void concurrentWorkersSendOnceAndConcurrentManualRetriesEnqueueOnce() throws Exception {
    queue();failCycle();advance(60);
    var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      Callable<Integer> retry=()->{ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();
        return mvc.perform(post(path("retry-email")).header("Authorization","Bearer "+jwt.generateToken(brand))).andReturn().getResponse().getStatus();};
      var a=pool.submit(retry);var b=pool.submit(retry);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
      assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
    }
    var sending=new CountDownLatch(1);var finish=new CountDownLatch(1);
    doAnswer(i->{sending.countDown();assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();return null;}).when(sender).sendCreatorClaimInvitation(anyString(),anyString());
    try(var pool=Executors.newFixedThreadPool(2)) {
      var first=pool.submit(()->worker.process(jobId));assertThat(sending.await(10,TimeUnit.SECONDS)).isTrue();
      var second=pool.submit(()->worker.process(jobId));
      try {second.get(10,TimeUnit.SECONDS);}finally{finish.countDown();}
      first.get(10,TimeUnit.SECONDS);
    }
    assertThat(job().getAttempts()).isEqualTo(6);assertThat(job().getStatus()).isEqualTo("SENT");
    verify(sender,times(6)).sendCreatorClaimInvitation(anyString(),anyString());
  }
  @Test void expiredLeaseCanBeRecoveredAndOldWorkerCannotCompleteNewLease() throws Exception {
    queue();var first=outbox.prepare(jobId);assertThat(outbox.prepare(jobId)).isNull();advance(120);
    assertThat(outbox.due()).contains(jobId);var replacement=outbox.prepare(jobId);
    assertThat(replacement.leaseToken()).isNotEqualTo(first.leaseToken());
    outbox.finish(first,true);assertThat(job().getStatus()).isEqualTo("PROCESSING");
    outbox.finish(replacement,true);assertThat(job().getStatus()).isEqualTo("SENT");assertThat(job().getAttempts()).isEqualTo(2);
    assertThat(job().getPayloadCiphertext()).isNull();
  }
  @Test void fiveCrashedReservationsExhaustCycleWithoutUnboundedRecoveryAttempts() throws Exception {
    queue();
    for(int i=0;i<5;i++){assertThat(outbox.prepare(jobId)).isNotNull();advance(120);}
    assertThat(outbox.prepare(jobId)).isNull();assertThat(job().getStatus()).isEqualTo("FAILED");
    assertThat(job().getAttempts()).isEqualTo(5);assertThat(job().getPayloadCiphertext()).isNotNull();
    assertThat(job().getLeaseToken()).isNull();verify(sender,never()).sendCreatorClaimInvitation(anyString(),anyString());
  }
  @ParameterizedTest @ValueSource(strings={"expired","accepted","replaced","owned","failedExpired"})
  void invalidJobsAreCancelledWithoutSmtpAndPayloadIsErased(String reason) throws Exception {
    queue();
    if(reason.equals("failedExpired"))failCycle();
    var invitation=invitations.findById(invitationId).orElseThrow();
    switch(reason) {
      case "expired","failedExpired" -> advance(7*86400);
      case "accepted" -> {invitation.setStatus("ACCEPTED");invitations.saveAndFlush(invitation);}
      case "replaced" -> {invitation.setTokenHash("b".repeat(64));invitations.saveAndFlush(invitation);}
      case "owned" -> {
        channel.setMaster(masters.saveAndFlush(CreatorMaster.builder().user(creator).canonicalName("Verified owner").status("ACTIVE").build()));
        channel.setSourceType(CreatorSourceType.AUTHORIZED);channel.setAuthorizedAt(now());creators.saveAndFlush(channel);
      }
    }
    clearInvocations(sender);worker.process(jobId);verifyNoInteractions(sender);
    assertThat(job().getStatus()).isIn("EXPIRED","CANCELLED");assertThat(job().getPayloadCiphertext()).isNull();
    call(brand,post(path("retry-email")),null,409);
  }
  @Test void duplicateOutboxIsRejectedAndInsertFailureRollsBackInvitation() throws Exception {
    String suffix=UUID.randomUUID().toString().replace("-","");String fn="outbox_fail_"+suffix,tr="outbox_fail_tr_"+suffix;
    jdbc.execute("create function "+fn+"() returns trigger language plpgsql as $$ begin raise exception 'outbox failure'; end $$");
    jdbc.execute("create trigger "+tr+" before insert on claim_email_outbox for each row execute function "+fn+"()");
    try {call(brand,post("/creators/"+channel.getId()+"/invite"),Map.of("email",creator.getEmail()),500);
      assertThat(invitations.findByTargetCreatorId(channel.getId())).isEmpty();
      verify(sender,never()).sendCreatorClaimInvitation(anyString(),anyString());
    } finally {jdbc.execute("drop trigger "+tr+" on claim_email_outbox");jdbc.execute("drop function "+fn+"()");}
    queue();
    assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->{
      var original=jobs.findById(jobId).orElseThrow();jobs.saveAndFlush(ClaimEmailJob.builder().invitation(original.getInvitation())
          .tokenHash(original.getTokenHash()).status("QUEUED").expiresAt(now().plusDays(7)).build());return null;
    })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
  @Test void statusAndRetryRequireOwningBrandAndPermissionAndHistoricalRowsAreNotTracked() throws Exception {
    queue();
    call(null,get(path("email-delivery")),null,401);call(null,post(path("retry-email")),null,401);
    for(Role role:List.of(Role.ADMIN,Role.CREATOR,Role.DATA_MANAGER)) {
      User actor=account(role);call(actor,get(path("email-delivery")),null,403);call(actor,post(path("retry-email")),null,403);
    }
    User other=account(Role.BRAND);createBrand(other);
    call(other,get(path("email-delivery")),null,404);call(other,post(path("retry-email")),null,404);
    UUID existing=invitationId;invitationId=UUID.randomUUID();
    call(brand,get(path("email-delivery")),null,404);call(brand,post(path("retry-email")),null,404);invitationId=existing;
    jdbc.update("insert into role_permission_override(id,role,permission,enabled) values(?,'BRAND','MANAGE_OWN_SHORTLIST',false)",UUID.randomUUID());
    try {call(brand,get(path("email-delivery")),null,403);call(brand,post(path("retry-email")),null,403);}
    finally {jdbc.update("delete from role_permission_override where role='BRAND' and permission='MANAGE_OWN_SHORTLIST'");}
    jobs.deleteById(jobId);
    assertThat(call(brand,get(path("email-delivery")),null,200).path("data").path("status").asText()).isEqualTo("NOT_TRACKED");
    call(brand,post(path("retry-email")),null,409);
  }
  @Test void manualRetryUsesRollingHourAndDoesNotChangeInvitation() throws Exception {
    queue();
    // Set up a previously exhausted cycle without repeating SMTP fifty times.
    new TransactionTemplate(transactions).executeWithoutResult(s->{
      var job=jobs.findById(jobId).orElseThrow();job.setStatus("FAILED");job.setLastAttemptAt(now().minusSeconds(60));
      job.setCycleAttempts(5);job.setAttempts(30);
      for(int i=0;i<5;i++)job.getManualRetryTimes().add(now().minusMinutes(59).plusSeconds(i));
    });
    call(brand,post(path("retry-email")),null,429);advance(60);
    call(brand,post(path("retry-email")),null,200);
    assertThat(job().getAttempts()).isEqualTo(30);assertThat(job().getCycleAttempts()).isZero();
  }
  @Test void upgradeFromV15PreservesHistoricalInvitationsWithoutInventingEmailJobs() {
    String schema="outbox_upgrade_"+UUID.randomUUID().toString().replace("-","");
    var old=org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("15").load();
    try {
      old.migrate();UUID user=UUID.randomUUID(),account=UUID.randomUUID(),invitation=UUID.randomUUID();
      jdbc.update("insert into "+schema+".\"user\"(id,email,password_hash,full_name,role,status,created_at,updated_at) values(?,'old@example.test','old-hash','Old Brand','BRAND','ACTIVE',now(),now())",user);
      jdbc.update("insert into "+schema+".creator(id,platform,external_id,user_name,created_at,updated_at) values(?,'TIKTOK',?,'old-channel',now(),now())",account,account.toString());
      jdbc.update("insert into "+schema+".creator_invitation(id,target_creator_id,invited_by,invited_email,token_hash,expires_at) values(?,?,?,'old@example.test',?,now()+interval '7 days')",invitation,account,user,"c".repeat(64));
      var before=jdbc.queryForObject("select to_jsonb(i)::text from "+schema+".creator_invitation i where id=?",String.class,invitation);
      org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("16").load().migrate();
      assertThat(jdbc.queryForObject("select to_jsonb(i)::text from "+schema+".creator_invitation i where id=?",String.class,invitation)).isEqualTo(before);
      assertThat(jdbc.queryForObject("select count(*) from "+schema+".claim_email_outbox",Long.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from "+schema+".\"user\" where id=? and email_verified=true",Long.class,user)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from "+schema+".creator where id=?",Long.class,account)).isEqualTo(1);
    } finally {jdbc.execute("drop schema if exists "+schema+" cascade");}
  }
}
