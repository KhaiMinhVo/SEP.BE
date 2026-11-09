package com.influencermatch.backend.creator.meta;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestPropertySource(
    properties = {
      "application.meta.enabled=false",
      "application.instagram.enabled=true",
      "application.instagram.app-id=789",
      "application.instagram.app-secret=private-ig-secret",
      "application.instagram.graph-api-version=v26.0",
      "application.instagram.redirect-uri=https://test.example/api/v1/integrations/instagram/callback",
      "application.instagram.encryption-key=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
    })
abstract class DirectInstagramTestSupport extends PostgresApiTestSupport {
  @MockBean InstagramGraphClient instagram;
  @MockBean MetaGraphClient facebook;
  @MockBean Clock clock;
  @Autowired CreatorRepository creators;
  @Autowired CreatorMasterRepository masters;
  @Autowired CreatorInvitationRepository invitations;
  @Autowired CreatorAccountVerificationRepository verifications;
  @Autowired MetaOAuthSessionRepository sessions;
  @Autowired MetaSessionStore store;
  @Autowired MetaPayloadCipher cipher;
  User creator, other;
  Creator channel;
  Instant now;

  record Started(UUID id, String state, jakarta.servlet.http.Cookie cookie) {}

  @BeforeEach
  void setupDirect() {
    now = InstagramGraphClientTest.NOW;
    when(clock.instant()).thenAnswer(i -> now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    creator = account(Role.CREATOR);
    other = account(Role.CREATOR);
    channel =
        creators.saveAndFlush(
            Creator.builder()
                .platform("INSTAGRAM")
                .externalId(Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits()))
                .userName("only_ig")
                .isStableId(true)
                .sourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.PUBLIC)
                .build());
    when(instagram.exchange(anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return directProof();
            });
    when(instagram.verify(any()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return directProof();
            });
  }

  MetaDtos.Proof directProof() {
    return new MetaDtos.Proof(
        "999",
        "private-ig-token",
        now.plusSeconds(600),
        Set.of("instagram_business_basic"),
        List.of(new MetaDtos.Asset(channel.getExternalId(), null, "only_ig", "only_ig")));
  }

  Started startDirect(User actor, Map<String, Object> body) throws Exception {
    return startAt(
        actor, "/creators/portal/accounts/connect/instagram/start", body, "www.instagram.com");
  }

  Started startAt(User actor, String path, Map<String, Object> body, String host) throws Exception {
    var result =
        mvc.perform(
                post(path)
                    .header("Authorization", "Bearer " + jwt.generateToken(actor))
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(body)))
            .andReturn()
            .getResponse();
    assertThat(result.getStatus()).isEqualTo(200);
    var data = json.readTree(result.getContentAsString()).path("data");
    URI uri = URI.create(data.path("authorizationUrl").asText());
    assertThat(uri.getHost()).isEqualTo(host);
    String state =
        Arrays.stream(uri.getRawQuery().split("&"))
            .filter(s -> s.startsWith("state="))
            .map(s -> URLDecoder.decode(s.substring(6), StandardCharsets.UTF_8))
            .findFirst()
            .orElseThrow();
    String header = result.getHeader("Set-Cookie");
    assertThat(header).contains("HttpOnly", "SameSite=Lax");
    if (host.equals("www.instagram.com"))
      assertThat(header).contains("Secure", "Path=/api/v1/integrations/instagram/callback");
    var parsed = HttpCookie.parse(header).getFirst();
    UUID id = UUID.fromString(data.path("sessionId").asText());
    assertThat(sessions.findById(id).orElseThrow().getStateHash()).isNotEqualTo(state);
    return new Started(
        id, state, new jakarta.servlet.http.Cookie(parsed.getName(), parsed.getValue()));
  }

  com.fasterxml.jackson.databind.JsonNode callback(Started s, int expected) throws Exception {
    return call(
        null,
        get("/integrations/instagram/callback")
            .cookie(s.cookie())
            .param("state", s.state())
            .param("code", "one-time-code"),
        null,
        expected);
  }

  Started readyDirect() throws Exception {
    var s = startDirect(creator, Map.of());
    var result = callback(s, 200);
    assertThat(result.path("data").path("status").asText()).isEqualTo("READY");
    assertThat(result.toString())
        .doesNotContain("private-ig-token", "only_ig", "accounts", "permissions");
    return s;
  }

  String path(Started s) {
    return "/creators/portal/accounts/connect/meta/sessions/" + s.id();
  }

  com.fasterxml.jackson.databind.JsonNode confirm(Started s, String accountId, int expected)
      throws Exception {
    return call(creator, post(path(s) + "/confirm"), Map.of("accountId", accountId), expected);
  }

  void unowned() {
    assertThat(creators.findById(channel.getId()).orElseThrow().getMaster()).isNull();
    assertThat(verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE")).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_provider_identity where creator_id=?",
                Long.class,
                channel.getId()))
        .isZero();
  }

  CreatorInvitation invitation(User invitee, LocalDateTime expires) {
    return invitations.saveAndFlush(
        CreatorInvitation.builder()
            .targetCreator(channel)
            .invitedBy(creator)
            .invitedEmail(invitee.getEmail())
            .tokenHash(UUID.randomUUID().toString())
            .status("PENDING")
            .expiresAt(expires)
            .build());
  }
}
