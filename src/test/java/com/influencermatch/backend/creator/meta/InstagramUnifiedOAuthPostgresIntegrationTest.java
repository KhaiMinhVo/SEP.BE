package com.influencermatch.backend.creator.meta;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real PostgreSQL/session/ownership integration; Meta HTTP is mocked separately. */
@TestPropertySource(properties = {
    "application.instagram-unified.enabled=true",
    "application.instagram-unified.app-id=976403021867954",
    "application.instagram-unified.app-secret=fixture-unified-secret",
    "application.instagram-unified.login-config-id=1176260962020417",
    "application.instagram-unified.graph-api-version=v26.0",
    "application.instagram-unified.redirect-uri=https://test.example/api/v1/integrations/instagram-unified/callback",
    "application.instagram-unified.encryption-key=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
})
class InstagramUnifiedOAuthPostgresIntegrationTest extends DirectInstagramTestSupport {
  @MockBean InstagramUnifiedGraphClient unified;

  private MetaDtos.Proof proof() {
    return new MetaDtos.Proof("998877:777", "fixture-suat", now.plusSeconds(600),
        Set.of("instagram_basic"), List.of(new MetaDtos.Asset(channel.getExternalId(), null, "only_ig", "only_ig")));
  }
  @BeforeEach void setupUnified() {
    when(unified.exchange(anyString())).thenAnswer(i -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return proof();
    });
    when(unified.verify(any())).thenAnswer(i -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return proof();
    });
  }
  private Started start() throws Exception {
    return startAt(creator, "/creators/portal/accounts/connect/instagram-unified/start", Map.of(), "www.facebook.com");
  }
  private void completeCallback(Started s, int status) throws Exception {
    var result = call(null, get("/integrations/instagram-unified/callback").cookie(s.cookie())
        .param("state", s.state()).param("code", "fixture-code"), null, status);
    assertThat(result.toString()).doesNotContain("fixture-suat", "fixture-code", "fixture-unified-secret");
  }
  @Test void publicApiContextCallbackAndCookiePathMatchTheUnifiedContract() throws Exception {
    var response = mvc.perform(post("/creators/portal/accounts/connect/instagram-unified/start")
        .header("Authorization", "Bearer " + jwt.generateToken(creator))
        .contentType("application/json").content("{}"))
        .andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Set-Cookie")).contains("Secure", "HttpOnly", "SameSite=Lax",
        "Path=/api/v1/integrations/instagram-unified/callback");
    assertThat(json.readTree(response.getContentAsString()).path("data").path("authorizationUrl").asText())
        .contains("config_id=1176260962020417", "prompt=login", "response_type=code")
        .doesNotContain("scope=", "fixture-unified-secret");
    var callback = mvc.perform(get("/api/v1/integrations/instagram-unified/callback")
        .contextPath("/api/v1").param("state", "invalid"))
        .andReturn().getResponse();
    assertThat(callback.getStatus()).isEqualTo(400); // reached public handler, not 401/404
    verifyNoInteractions(unified); unowned();
  }
  @Test void migrationAllowsUnifiedAndConfirmationPersistsDistinctProvenanceWithoutTokens() throws Exception {
    var s = start();
    assertThat(sessions.findById(s.id()).orElseThrow().getOauthFlow()).isEqualTo(OAuthFlow.INSTAGRAM_UNIFIED_LOGIN);
    completeCallback(s, 200);
    var view = call(creator, get(path(s)), null, 200).path("data");
    assertThat(view.path("oauthFlow").asText()).isEqualTo("INSTAGRAM_UNIFIED_LOGIN");
    assertThat(view.path("accounts").get(0).path("accountId").asText()).isEqualTo(channel.getExternalId());
    assertThat(view.toString()).doesNotContain("fixture-suat", "tokenExpiresAt", "subject");
    confirm(s, channel.getExternalId(), 200);
    var verification = verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE").getFirst();
    assertThat(verification.getVerificationMethod()).isEqualTo("INSTAGRAM_UNIFIED_OAUTH");
    assertThat(verification.getAccessTokenEncrypted()).isNull();
    assertThat(verification.getRefreshTokenEncrypted()).isNull();
    assertThat(sessions.findById(s.id()).orElseThrow().getPayloadCiphertext()).isNull();
    confirm(s, channel.getExternalId(), 409);
    verify(unified, times(1)).verify(any()); verifyNoInteractions(facebook);
  }
  @Test void wrongCallbackAndMissingBrowserBindingCannotExchangeCode() throws Exception {
    var s = start();
    call(null, get("/integrations/instagram-unified/callback").param("state", s.state()).param("code", "fixture-code"), null, 403);
    call(null, get("/integrations/instagram/callback").cookie(s.cookie()).param("state", s.state()).param("code", "fixture-code"), null, 400);
    verifyNoInteractions(unified); unowned();
    completeCallback(s, 200); completeCallback(s, 409);
    verify(unified, times(1)).exchange(anyString());
  }
  @Test void revokedGrantAndForeignSelectionNeverChangeOwnership() throws Exception {
    var s = start(); completeCallback(s, 200);
    confirm(s, "111", 409); unowned();
    when(unified.verify(any())).thenReturn(new MetaDtos.Proof("998877:777", "fixture-suat", now.plusSeconds(600), Set.of("instagram_basic"), List.of()));
    confirm(s, channel.getExternalId(), 409); unowned();
  }
  @Test void cancelAndExpiryPreventLateConfirmation() throws Exception {
    var s = start(); completeCallback(s, 200);
    call(creator, post(path(s) + "/cancel"), null, 200);
    confirm(s, channel.getExternalId(), 409); unowned();
    var expired = start(); now = now.plusSeconds(601);
    completeCallback(expired, 409); unowned();
  }
}
