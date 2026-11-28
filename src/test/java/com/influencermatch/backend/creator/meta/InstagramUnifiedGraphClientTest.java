package com.influencermatch.backend.creator.meta;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.influencermatch.backend.exception.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class InstagramUnifiedGraphClientTest {
  private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");
  private static final String KEY = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=";
  private static final String TOKEN = "private-unified-token";
  private static final String SECRET = "private-unified-secret";

  private static InstagramUnifiedOAuthProperties config() {
    return new InstagramUnifiedOAuthProperties(
        true,
        "976403021867954",
        SECRET,
        "1176260962020417",
        "v26.0",
        "https://test.example/api/v1/integrations/instagram-unified/callback",
        KEY);
  }

  private MockRestServiceServer server;
  private InstagramUnifiedGraphClient graph;

  @BeforeEach
  void setUp() {
    var builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    graph = new InstagramUnifiedGraphClient(config(), Clock.fixed(NOW, ZoneOffset.UTC), builder.build());
  }

  private void meResponse(String body) {
    server.expect(requestTo(org.hamcrest.Matchers.containsString("/debug_token?")))
        .andExpect(queryParam("input_token", TOKEN))
        .andExpect(header("Authorization", "Bearer 976403021867954|" + SECRET))
        .andRespond(withSuccess("{\"data\":{\"is_valid\":true,\"app_id\":\"976403021867954\",\"scopes\":[\"instagram_basic\"],\"expires_at\":0}}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://graph.facebook.com/v26.0/me?fields=client_business_id"))
        .andExpect(header("Authorization", "Bearer " + TOKEN))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  private void assetsResponse(String body) {
    server.expect(requestTo(org.hamcrest.Matchers.containsString(
            "https://graph.facebook.com/v26.0/me/assigned_instagram_accounts?fields=id,username&limit=100")))
        .andExpect(header("Authorization", "Bearer " + TOKEN))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  @Test
  void exchangesBusinessConfigurationCodeAndUsesAssignedInstagramAssetsForStableIds() {
    server.expect(requestTo(org.hamcrest.Matchers.containsString(
            "https://graph.facebook.com/v26.0/oauth/access_token?")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(queryParam("client_id", "976403021867954"))
        .andExpect(queryParam("client_secret", SECRET))
        .andExpect(queryParam("code", "one-time-code"))
        .andRespond(withSuccess("{\"access_token\":\"" + TOKEN + "\"}", MediaType.APPLICATION_JSON));
    meResponse("{\"id\":\"777\",\"client_business_id\":\"998877\"}");
    assetsResponse(
        "{\"data\":["
            + "{\"id\":\"178400001\",\"username\":\"creator_one\",\"name\":\"Creator One\"},"
            + "{\"id\":\"178400002\",\"username\":\"creator_two\",\"name\":\"Creator Two\"}"
            + "]}");

    var proof = graph.exchange("one-time-code");

    assertThat(proof.subject()).isEqualTo("998877:777");
    assertThat(proof.assets())
        .extracting(MetaDtos.Asset::id)
        .containsExactly("178400001", "178400002");
    assertThat(proof.assets().getFirst().pageId()).isNull();
    assertThat(proof.assets().getFirst().username()).isEqualTo("creator_one");
    assertThat(proof.scopes()).containsExactly("instagram_basic");
    assertThat(proof.tokenExpiresAt()).isEqualTo(NOW.plusSeconds(600));
    assertThat(proof.toString()).doesNotContain(TOKEN, SECRET);
    server.verify();
  }

  @Test
  void rechecksBusinessAndGrantedAssetsBeforeClaimConfirmation() {
    meResponse("{\"id\":\"777\",\"client_business_id\":\"998877\"}");
    assetsResponse("{\"data\":[{\"id\":\"178400001\",\"username\":\"creator_one\"}]}");
    var previous =
        new MetaDtos.Proof(
            "998877:777",
            TOKEN,
            NOW.plusSeconds(600),
            java.util.Set.of(),
            java.util.List.of());

    var fresh = graph.verify(previous);

    assertThat(fresh.assets()).extracting(MetaDtos.Asset::id).containsExactly("178400001");
    server.verify();
  }

  @Test
  void rejectsTokensWithoutBusinessIntegrationContext() {
    meResponse("{\"id\":\"777\"}");

    assertThatThrownBy(() -> graph.verify(previous()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.META_AUTHORIZATION_REJECTED));
    server.verify();
  }

  @Test
  void rejectsWhenInstagramAssetPermissionOrSelectionIsUnavailable() {
    meResponse("{\"id\":\"777\",\"client_business_id\":\"998877\"}");
    server.expect(requestTo(org.hamcrest.Matchers.containsString(
            "/me/assigned_instagram_accounts?fields=id,username&limit=100")))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).body("provider details"));

    assertThatThrownBy(() -> graph.verify(previous()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.META_AUTHORIZATION_REJECTED);
              assertThat(error.getMessage()).doesNotContain("provider details", TOKEN, SECRET);
              assertThat(error.getCause()).isNull();
            });
    server.verify();
  }

  @Test
  void rejectsBusinessIdentityChangeDuringFreshVerification() {
    meResponse("{\"id\":\"777\",\"client_business_id\":\"998878\"}");
    assetsResponse("{\"data\":[]}");
    var previous =
        new MetaDtos.Proof(
            "998877:777",
            TOKEN,
            NOW.plusSeconds(600),
            java.util.Set.of(),
            java.util.List.of());

    assertThatThrownBy(() -> graph.verify(previous))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.META_AUTHORIZATION_REJECTED));
    server.verify();
  }

  private MetaDtos.Proof previous() {
    return new MetaDtos.Proof("998877:777", TOKEN, NOW.plusSeconds(600), java.util.Set.of("instagram_basic"), java.util.List.of());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "\"is_valid\":false,\"app_id\":\"976403021867954\",\"scopes\":[\"instagram_basic\"]",
      "\"is_valid\":true,\"app_id\":\"wrong\",\"scopes\":[\"instagram_basic\"]",
      "\"is_valid\":true,\"app_id\":\"976403021867954\",\"scopes\":[\"instagram_content_publish\"]",
      "\"is_valid\":true,\"app_id\":\"976403021867954\",\"scopes\":[\"instagram_basic\"],\"type\":\"USER\"",
      "\"is_valid\":true,\"app_id\":\"976403021867954\",\"scopes\":[\"instagram_basic\"],\"expires_at\":-1",
      "\"is_valid\":true,\"app_id\":\"976403021867954\",\"scopes\":[\"instagram_basic\"],\"expires_at\":1"
  })
  void rejectsWrongAppPermissionTokenClassOrExpiry(String fields) {
    server.expect(requestTo(org.hamcrest.Matchers.containsString("/debug_token?")))
        .andRespond(withSuccess("{\"data\":{" + fields + "}}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> graph.verify(previous())).isInstanceOfSatisfying(BusinessException.class,
        error -> assertThat(error.code()).isEqualTo(ErrorCode.META_AUTHORIZATION_REJECTED));
    server.verify();
  }

  @Test void restrictsAssignedAssetsByGranularGrant() {
    server.expect(requestTo(org.hamcrest.Matchers.containsString("/debug_token?")))
        .andRespond(withSuccess("{\"data\":{\"is_valid\":true,\"app_id\":\"976403021867954\",\"scopes\":[\"instagram_basic\"],\"granular_scopes\":[{\"scope\":\"instagram_basic\",\"target_ids\":[\"178400002\"]}]}}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://graph.facebook.com/v26.0/me?fields=client_business_id"))
        .andRespond(withSuccess("{\"id\":\"777\",\"client_business_id\":\"998877\"}", MediaType.APPLICATION_JSON));
    assetsResponse("{\"data\":[{\"id\":\"178400001\"},{\"id\":\"178400002\"}]}");
    assertThat(graph.verify(previous()).assets()).extracting(MetaDtos.Asset::id).containsExactly("178400002");
    server.verify();
  }

  @Test void followsOnlyCursorOnTheFixedGraphEndpoint() {
    meResponse("{\"id\":\"777\",\"client_business_id\":\"998877\"}");
    assetsResponse("{\"data\":[{\"id\":\"178400001\"}],\"paging\":{\"next\":\"https://untrusted.invalid/private\",\"cursors\":{\"after\":\"cursor-2\"}}}");
    server.expect(requestTo("https://graph.facebook.com/v26.0/me/assigned_instagram_accounts?fields=id,username&limit=100&after=cursor-2"))
        .andExpect(header("Authorization", "Bearer " + TOKEN))
        .andRespond(withSuccess("{\"data\":[{\"id\":\"178400002\"}]}", MediaType.APPLICATION_JSON));
    assertThat(graph.verify(previous()).assets()).extracting(MetaDtos.Asset::id).containsExactly("178400001", "178400002");
    server.verify();
  }

  @Test void providerFailureIsSanitized() {
    server.expect(requestTo(org.hamcrest.Matchers.containsString("/debug_token?")))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(TOKEN + SECRET));
    assertThatThrownBy(() -> graph.verify(previous())).isInstanceOfSatisfying(BusinessException.class, error -> {
      assertThat(error.code()).isEqualTo(ErrorCode.META_PROVIDER_ERROR);
      assertThat(error.getMessage()).doesNotContain(TOKEN, SECRET);
      assertThat(error.getCause()).isNull();
    });
    server.verify();
  }
}
