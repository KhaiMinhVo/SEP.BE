package com.influencermatch.backend.creator.tiktok;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.creator.client.provider.TikTokSandboxProofProvider;
import com.influencermatch.backend.creator.client.provider.TikTokSandboxProofProvider.VerifiedAccount;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import jakarta.servlet.http.Cookie;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestPropertySource(
    properties = {
      "application.tiktok.enabled=true",
      "application.tiktok.client-key=sandbox-key",
      "application.tiktok.client-secret=private-secret",
      "application.tiktok.redirect-uri=https://test.example/api/v1/integrations/tiktok/callback",
      "application.tiktok.scope=user.info.basic,user.info.profile"
    })
abstract class TikTokTestSupport extends PostgresApiTestSupport {
  static final String START = "/creators/portal/accounts/connect/tiktok/start",
      CALLBACK = "/integrations/tiktok/callback",
      SESSIONS = "/creators/portal/accounts/connect/tiktok/sessions/";
  @MockBean TikTokSandboxProofProvider provider;
  @MockBean Clock clock;
  @org.springframework.boot.test.mock.mockito.SpyBean TikTokLoginProperties config;
  @Autowired TikTokSessionStore store;
  @Autowired CreatorRepository creators;
  @Autowired CreatorMasterRepository masters;
  @Autowired CreatorInvitationRepository invitations;
  Instant now;
  User actor, other;
  Creator channel;

  record Started(UUID id, String state, Cookie cookie) {}

  @BeforeEach
  void setup() {
    now = Instant.parse("2026-10-26T10:00:00Z");
    when(clock.instant()).thenAnswer(i -> now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    actor = account(Role.CREATOR);
    other = account(Role.CREATOR);
    channel =
        creators.saveAndFlush(
            Creator.builder()
                .platform("TIKTOK")
                .externalId(
                    Long.toString(
                        java.util.concurrent.ThreadLocalRandom.current()
                            .nextLong(100000000000000000L, 900000000000000000L)))
                .userName("crawler_name")
                .isStableId(true)
                .build());
    when(crawler.resolveTikTokIdentity(anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return resolved("9" + channel.getExternalId(), i.getArgument(0));
            });
    when(provider.verify(anyString()))
        .thenAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new VerifiedAccount("app-scoped-id", "real_username", "Real name");
            });
  }

  com.influencermatch.backend.creator.dto.CrawlerResolveAuthorResponse resolved(
      String id, String username) {
    var info =
        new com.influencermatch.backend.creator.dto.CrawlerResolveAuthorResponse.CreatorInfo();
    info.setDisplayName("Real profile");
    info.setAvatarUrl("https://cdn.example/avatar.jpg");
    return com.influencermatch.backend.creator.dto.CrawlerResolveAuthorResponse.builder()
        .status("success")
        .platform("TIKTOK")
        .idType("TIKTOK_USER_ID")
        .authorId(id)
        .resolvedUsername(username)
        .observedAt(now)
        .creatorInfo(info)
        .build();
  }

  Started start(Map<String, Object> body) throws Exception {
    var response =
        mvc.perform(
                post(START)
                    .header("Authorization", "Bearer " + jwt.generateToken(actor))
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    var data = json.readTree(response.getContentAsString()).path("data");
    URI url = URI.create(data.path("authorizationUrl").asText());
    assertThat(url.getHost()).isEqualTo("www.tiktok.com");
    String state =
        Arrays.stream(url.getRawQuery().split("&"))
            .filter(q -> q.startsWith("state="))
            .map(q -> URLDecoder.decode(q.substring(6), StandardCharsets.UTF_8))
            .findFirst()
            .orElseThrow();
    String cookie = response.getHeader("Set-Cookie");
    assertThat(cookie)
        .contains(
            "HttpOnly",
            "Secure",
            "SameSite=Lax",
            "Max-Age=600",
            "Path=/api/v1/integrations/tiktok/callback");
    String[] pair = cookie.split(";", 2)[0].split("=", 2);
    return new Started(
        UUID.fromString(data.path("sessionId").asText()), state, new Cookie(pair[0], pair[1]));
  }

  com.fasterxml.jackson.databind.JsonNode callback(Started s, int expected) throws Exception {
    return call(
        null,
        get(CALLBACK).param("state", s.state()).param("code", "real-code").cookie(s.cookie()),
        null,
        expected);
  }

  String row(UUID id) {
    return jdbc.queryForObject(
        "select to_jsonb(t)::text from tiktok_oauth_session t where id=?", String.class, id);
  }

  String businessRows() {
    var out = new StringBuilder();
    for (String table :
        List.of(
            "user",
            "creator",
            "creator_master",
            "creator_invitation",
            "creator_account_verification",
            "creator_provider_identity")) {
      out.append(
          jdbc.queryForList(
              "select to_jsonb(t)::text from \"" + table + "\" t order by id", String.class));
    }
    return out.toString();
  }

  CreatorInvitation invitation(String email, Instant expires) {
    var invitation =
        invitations.saveAndFlush(
            CreatorInvitation.builder()
                .targetCreator(channel)
                .invitedBy(account(Role.BRAND))
                .invitedEmail(email)
                .tokenHash(CreatorInvitationService.hashToken(UUID.randomUUID().toString()))
                .expiresAt(LocalDateTime.ofInstant(expires, ZoneOffset.UTC))
                .build());
    invitation.setTokenHash(CreatorInvitationService.hashToken("invitation-" + invitation.getId()));
    return invitations.saveAndFlush(invitation);
  }
}
