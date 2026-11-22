package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.email.ClaimEmailCipher;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class CooperationResendTest {
  final DealStore store = mock(DealStore.class);
  final CooperationDelivery delivery = spy(new CooperationDelivery(store, mock(ClaimEmailCipher.class), mock(VerificationEmailSender.class), mock(ApplicationEventPublisher.class)));
  final UUID deal = UUID.randomUUID();
  final Map<String,Object> invitation = Map.of("deal_id", deal, "proposal_revision", 1L);
  final Instant now = Instant.parse("2026-11-21T10:00:00Z");
  CooperationResendTest() {
    when(store.now()).thenReturn(now);
    when(store.deal(deal)).thenReturn(Map.of("status", "NEGOTIATING"));
    when(store.decode(any())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode());
    doReturn(true).when(delivery).valid(invitation);
  }
  Map<String,Object> job(String status, long secondsAgo) {
    return Map.of("status",status,"payload","encrypted","last_attempt_at",java.sql.Timestamp.from(now.minusSeconds(secondsAgo)),"manual_retries","[]");
  }
  @Test void sentEmailCanBeResentButNotRetriedAsFailure() {
    assertThat(delivery.canResend(invitation, job("SENT", 61))).isTrue();
    assertThat(delivery.canRetry(invitation, job("SENT", 61))).isFalse();
    assertThat(delivery.canRetry(invitation, job("FAILED", 61))).isTrue();
  }
  @Test void queuePortalAndManualJobsCannotBeResent() {
    for (var state : List.of("QUEUED","PROCESSING","FAILED","MANUAL_REQUIRED","NOT_REQUESTED","CANCELLED"))
      assertThat(delivery.canResend(invitation, job(state, 61))).isFalse();
  }
  @Test void cooldownExpiredInvitationAndConsentBlockResend() {
    assertThat(delivery.canResend(invitation, job("SENT", 59))).isFalse();
    doReturn(false).when(delivery).valid(invitation);
    assertThat(delivery.canResend(invitation, job("SENT", 61))).isFalse();
    doReturn(true).when(delivery).valid(invitation);
    when(store.deal(deal)).thenReturn(Map.of("accepted_revision", 1L));
    assertThat(delivery.canResend(invitation, job("SENT", 61))).isFalse();
  }
  @Test void retryBudgetIsSharedAndLimitedToFivePerHour() {
    var times = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
    for (int i=0;i<5;i++) times.add(now.minusSeconds(120+i).toString());
    when(store.decode(any())).thenReturn(times);
    assertThat(delivery.canResend(invitation, job("SENT", 61))).isFalse();
    assertThat(delivery.canRetry(invitation, job("FAILED", 61))).isFalse();
  }
}
