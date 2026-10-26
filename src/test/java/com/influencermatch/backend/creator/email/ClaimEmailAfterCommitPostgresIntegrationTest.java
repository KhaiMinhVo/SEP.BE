package com.influencermatch.backend.creator.email;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import com.influencermatch.backend.user.enums.Role;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties="application.claim-email.dispatch-enabled=true")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ClaimEmailAfterCommitPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean VerificationEmailSender sender;
  @MockBean ClaimEmailWorker worker;
  @MockBean Clock clock;
  @SpyBean ClaimEmailOutboxService outbox;
  @Autowired ClaimEmailJobRepository jobs;
  @Autowired CreatorRepository creators;
  @Autowired CreatorInvitationRepository invitations;
  @Autowired PlatformTransactionManager transactions;
  @Test void dispatchOccursOnlyAfterCommitAndRollbackDiscardsJobAndEvent() {
    when(clock.instant()).thenReturn(Instant.parse("2026-10-23T00:00:00Z"));when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    doReturn(java.util.List.of()).when(outbox).due();
    var user=account(Role.BRAND);
    var channel=creators.saveAndFlush(Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("after-commit").build());
    for(boolean commit:new boolean[]{true,false}) {
      var id=new AtomicReference<UUID>();var invitationId=new AtomicReference<UUID>();
      new TransactionTemplate(transactions).executeWithoutResult(tx->{
        var invitation=invitations.save(CreatorInvitation.builder().targetCreator(channel).invitedBy(user).invitedEmail("email@example.test")
            .status("PENDING").tokenHash(CreatorInvitationService.hashToken(UUID.randomUUID().toString())).expiresAt(LocalDateTime.now(clock).plusDays(7)).build());
        invitationId.set(invitation.getId());
        outbox.enqueue(invitation,"http://frontend.test/claim?token=secret");
        id.set(jobs.findByInvitationId(invitation.getId()).orElseThrow().getId());
        verify(worker,never()).sendAsync(id.get());
        if(!commit)tx.setRollbackOnly();
      });
      if(commit){verify(worker).sendAsync(id.get());assertThat(jobs.existsById(id.get())).isTrue();}
      else {verify(worker,never()).sendAsync(id.get());assertThat(jobs.existsById(id.get())).isFalse();assertThat(invitations.existsById(invitationId.get())).isFalse();}
    }
    verify(sender,never()).sendCreatorClaimInvitation(anyString(),anyString());
  }
}
