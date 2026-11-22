package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.email.ClaimEmailCipher;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class CooperationDeliveryLifecycleTest {
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final Instant now = Instant.parse("2026-11-21T12:00:00Z");
  final DealStore store = spy(new DealStore(db, new com.fasterxml.jackson.databind.ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC)));
  final CooperationDelivery delivery = spy(new CooperationDelivery(store, mock(ClaimEmailCipher.class), mock(VerificationEmailSender.class), mock(ApplicationEventPublisher.class)));

  @Test void sentEmailReportsActualCooldownExpiry() {
    UUID dealId = UUID.randomUUID();
    doReturn(true).when(delivery).valid(anyMap());
    doReturn(Map.of("accepted_revision", 0L)).when(store).deal(dealId);
    var invitation = Map.<String,Object>of("id", UUID.randomUUID(), "deal_id", dealId,
        "proposal_revision", 1L, "status", "ACTIVE");
    var job = Map.<String,Object>of("status", "SENT", "payload", "encrypted",
        "last_attempt_at", java.sql.Timestamp.from(now.minusSeconds(10)), "manual_retries", "[]");
    var result = (Map<?,?>) delivery.statusView(invitation, job);
    assertThat(result.get("canResend")).isEqualTo(false);
    assertThat(result.get("resendUnavailableReason")).isEqualTo("COOLDOWN");
    assertThat(result.get("resendAvailableAt")).isEqualTo(now.plusSeconds(50).toString());
  }

  @Test void successfulSendRetainsEncryptedPayloadForExplicitResend() {
    UUID id = UUID.randomUUID(), token = UUID.randomUUID();
    doReturn(Map.of("status","PROCESSING","processing_token",token,"lease_until",java.sql.Timestamp.from(now.plusSeconds(100)),"payload","encrypted"))
        .when(store).one(anyString(), eq(id));
    delivery.finish(new CooperationDelivery.Work(id, token, "encrypted"), true);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(db).update(sql.capture(), any(), eq(id));
    assertThat(sql.getValue()).contains("status='SENT'", "sent_at=?", "processing_token=null").doesNotContain("payload=null");
    verifyNoMoreInteractions(db);
  }

  @Test void sentJobWithRetainedPayloadIsNotDispatchedAgainByDuplicateSweep() {
    UUID id = UUID.randomUUID(), invitationId = UUID.randomUUID(), dealId = UUID.randomUUID();
    var invitation = Map.<String,Object>of("id", invitationId, "deal_id", dealId, "expires_at", java.sql.Timestamp.from(now.plusSeconds(3600)));
    var job = Map.<String,Object>of("id",id,"status","SENT","payload","encrypted");
    doReturn(Map.of("campaign_id", UUID.randomUUID())).when(store).deal(dealId);
    doAnswer(call -> call.getArgument(0, String.class).startsWith("select * from cooperation_email_job") ? job : invitation)
        .when(store).one(anyString(), any());
    doReturn(true).when(delivery).valid(invitation);
    assertThat(delivery.prepare(id)).isNull();
    verifyNoInteractions(db);
  }
}
