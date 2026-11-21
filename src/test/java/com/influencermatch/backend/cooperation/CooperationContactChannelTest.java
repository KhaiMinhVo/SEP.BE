package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.email.ClaimEmailCipher;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class CooperationContactChannelTest {
  final DealStore store = mock(DealStore.class);
  final VerificationEmailSender sender = mock(VerificationEmailSender.class);
  final CooperationDelivery delivery = new CooperationDelivery(store, mock(ClaimEmailCipher.class), sender, mock(ApplicationEventPublisher.class));

  @Test void linkedMasterPlansPortalWithoutSendingAnything() {
    assertThat(delivery.plannedContactChannel(Map.of("id", UUID.randomUUID(), "master_id", UUID.randomUUID()))).isEqualTo("IN_APP_PORTAL");
    verifyNoInteractions(sender, store);
  }

  @Test void publicEmailPlansEmailWithoutReturningRecipientAddress() {
    UUID deal = UUID.randomUUID(), creator = UUID.randomUUID();
    when(store.members(deal)).thenReturn(List.of(Map.of("creator_id", creator)));
    when(store.optional(anyString(), eq(creator))).thenReturn(Optional.of(Map.of("contact", "Booking: test@example.test")));
    assertThat(delivery.plannedContactChannel(Map.of("id", deal))).isEqualTo("SYSTEM_EMAIL_OUTBOX");
    verifyNoInteractions(sender);
  }

  @Test void missingOrInvalidEmailPlansManualShare() {
    UUID deal = UUID.randomUUID(), creator = UUID.randomUUID();
    when(store.members(deal)).thenReturn(List.of(Map.of("creator_id", creator)));
    when(store.optional(anyString(), eq(creator))).thenReturn(Optional.of(Map.of("contact", "DM me")));
    assertThat(delivery.plannedContactChannel(Map.of("id", deal))).isEqualTo("MANUAL_SOCIAL_LINK");
    verifyNoInteractions(sender);
  }

  @Test void unclaimedEmailAllowsEmailOrManualButNotPortal() {
    UUID deal = UUID.randomUUID(), creator = UUID.randomUUID();
    when(store.members(deal)).thenReturn(List.of(Map.of("creator_id", creator)));
    when(store.optional(anyString(), eq(creator))).thenReturn(Optional.of(Map.of("contact", "test@example.test")));
    assertThat(delivery.availableDeliveryMethods(Map.of("id", deal))).containsExactly("EMAIL_ONLY", "MANUAL_LINK");
  }

  @Test void linkedVerifiedCreatorAllowsFourChoices() {
    UUID master = UUID.randomUUID();
    when(store.optional(anyString(), eq(master))).thenReturn(Optional.of(Map.of("email", "test@example.test", "role", "CREATOR", "status", "ACTIVE", "email_verified", true)));
    assertThat(delivery.availableDeliveryMethods(Map.of("master_id", master))).containsExactly("PORTAL_EMAIL", "PORTAL_ONLY", "EMAIL_ONLY", "MANUAL_LINK");
  }

  @Test void missingEmailAllowsOnlyManualForUnclaimedChannel() {
    UUID deal = UUID.randomUUID(), creator = UUID.randomUUID();
    when(store.members(deal)).thenReturn(List.of(Map.of("creator_id", creator)));
    when(store.optional(anyString(), eq(creator))).thenReturn(Optional.empty());
    assertThat(delivery.availableDeliveryMethods(Map.of("id", deal))).containsExactly("MANUAL_LINK");
  }

  @Test void unsupportedChoicesAreRejectedBeforeSending() {
    assertThatThrownBy(() -> CooperationDelivery.validateDeliveryMethod("PORTAL_ONLY", false, true)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> CooperationDelivery.validateDeliveryMethod("EMAIL_ONLY", true, false)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> CooperationDelivery.validateDeliveryMethod("PORTAL_EMAIL", true, false)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> CooperationDelivery.validateDeliveryMethod("OTHER", true, true)).isInstanceOf(RuntimeException.class);
    assertThatCode(() -> CooperationDelivery.validateDeliveryMethod("MANUAL_LINK", false, false)).doesNotThrowAnyException();
    assertThatCode(() -> CooperationDelivery.validateDeliveryMethod("PORTAL_ONLY", true, false)).doesNotThrowAnyException();
    verifyNoInteractions(sender);
  }

  @Test void selectedMethodControlsEmailQueueAndPortalNotificationIndependently() {
    for (String method : List.of("PORTAL_EMAIL", "PORTAL_ONLY", "EMAIL_ONLY", "MANUAL_LINK")) {
      var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
      var records = spy(new DealStore(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Clock.systemUTC()));
      var cipher = mock(ClaimEmailCipher.class);
      var smtp = mock(VerificationEmailSender.class);
      var events = mock(ApplicationEventPublisher.class);
      var notifications = mock(com.influencermatch.backend.notification.service.NotificationService.class);
      var channel = new CooperationDelivery(records, cipher, smtp, events);
      channel.frontend = "https://frontend.example";
      channel.notificationService = notifications;
      UUID id = UUID.randomUUID(), master = UUID.randomUUID();
      doReturn(Optional.empty()).when(records).optional(anyString(), eq(id), eq(1L), any());
      doReturn(Map.of("id", UUID.randomUUID(), "email", "test@example.test", "role", "CREATOR", "status", "ACTIVE", "email_verified", true)).when(records).one(anyString(), eq(master));
      when(cipher.encrypt(anyString(), anyString())).thenReturn("encrypted");
      var result = channel.enqueue(Map.of("id", id, "master_id", master), Map.of("revision", 1L), method);
      boolean email = Set.of("PORTAL_EMAIL", "EMAIL_ONLY").contains(method);
      boolean portal = Set.of("PORTAL_EMAIL", "PORTAL_ONLY").contains(method);
      assertThat(result.get("deliveryMethod")).isEqualTo(method);
      assertThat(result.get("emailStatus")).isEqualTo(email ? "QUEUED" : method.equals("MANUAL_LINK") ? "MANUAL_REQUIRED" : "NOT_REQUESTED");
      assertThat(result.get("portalNotificationCreated")).isEqualTo(portal);
      if (email) { verify(smtp).requireConfigured(); verify(events).publishEvent(any(CooperationDelivery.Queued.class)); }
      else { verifyNoInteractions(smtp, events); }
      assertThat(mockingDetails(notifications).getInvocations().isEmpty()).isEqualTo(!portal);
    }
  }
}
