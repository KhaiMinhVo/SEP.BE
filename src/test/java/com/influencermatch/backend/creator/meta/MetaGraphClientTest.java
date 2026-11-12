package com.influencermatch.backend.creator.meta;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.influencermatch.backend.exception.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MetaGraphClientTest {
  static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
  static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

  static MetaOAuthProperties config() {
    return new MetaOAuthProperties(
        true,
        "123",
        "private-app-secret",
        "456",
        "v24.0",
        "http://localhost:8080/api/v1/integrations/meta/callback",
        KEY);
  }

  MockRestServiceServer server;
  MetaGraphClient graph;

  @BeforeEach
  void setup() {
    var builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    graph = new MetaGraphClient(config(), Clock.fixed(NOW, ZoneOffset.UTC), builder.build());
  }

  String debug(String platform) {
    return "{\"data\":{\"is_valid\":true,\"type\":\"USER\",\"app_id\":\"123\",\"user_id\":\"99\",\"expires_at\":"
        + NOW.plusSeconds(3600).getEpochSecond()
        + ",\"scopes\":[\"pages_show_list\",\"pages_read_engagement\",\"instagram_basic\"]}}";
  }

  void debugResponse(String body) {
    server
        .expect(
            requestTo(
                org.hamcrest.Matchers.startsWith("https://graph.facebook.com/v24.0/debug_token?")))
        .andExpect(header("Authorization", "Bearer 123|private-app-secret"))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  void userAndPermissions() {
    userAndPermissions(
        "{\"data\":[{\"permission\":\"pages_show_list\",\"status\":\"granted\"},{\"permission\":\"pages_read_engagement\",\"status\":\"granted\"},{\"permission\":\"instagram_basic\",\"status\":\"granted\"}]}");
  }

  void userAndPermissions(String permissions) {
    server
        .expect(requestTo("https://graph.facebook.com/v24.0/me?fields=id"))
        .andExpect(header("Authorization", "Bearer private-user-token"))
        .andRespond(withSuccess("{\"id\":\"99\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://graph.facebook.com/v24.0/me/permissions"))
        .andRespond(withSuccess(permissions, MediaType.APPLICATION_JSON));
  }

  @Test
  void exchangesOnceAndPagesUsingCursorOnFixedGraphOrigin() {
    server
        .expect(requestTo("https://graph.facebook.com/v24.0/oauth/access_token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("code=one-time-code")))
        .andRespond(
            withSuccess("{\"access_token\":\"private-user-token\"}", MediaType.APPLICATION_JSON));
    debugResponse(debug("FACEBOOK"));
    userAndPermissions();
    server
        .expect(
            requestTo(
                org.hamcrest.Matchers.startsWith("https://graph.facebook.com/v24.0/me/accounts?")))
        .andRespond(
            withSuccess(
                "{\"data\":[{\"id\":\"100\",\"name\":\"Page\",\"tasks\":[\"ANALYZE\"]}],\"paging\":{\"next\":\"https://evil.test/steal?access_token=private-user-token\",\"cursors\":{\"after\":\"next-cursor\"}}}",
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.startsWith(
                        "https://graph.facebook.com/v24.0/me/accounts?"),
                    org.hamcrest.Matchers.containsString("after=next-cursor"))))
        .andRespond(
            withSuccess(
                "{\"data\":[{\"id\":\"101\",\"name\":\"Second\",\"tasks\":[\"CREATE_CONTENT\"]}]}",
                MediaType.APPLICATION_JSON));
    var proof = graph.exchange("one-time-code", "FACEBOOK");
    assertThat(proof.assets()).extracting(MetaDtos.Asset::id).containsExactly("100", "101");
    assertThat(proof.toString()).doesNotContain("private-user-token");
    server.verify();
  }

  @Test
  void instagramUsesProfessionalIdNotItsPageIdAndSkipsUnlinkedPages() {
    debugResponse(debug("INSTAGRAM"));
    userAndPermissions();
    server
        .expect(
            requestTo(
                org.hamcrest.Matchers.startsWith("https://graph.facebook.com/v24.0/me/accounts?")))
        .andRespond(
            withSuccess(
                "{\"data\":[{\"id\":\"100\",\"tasks\":[\"MANAGE\"],\"instagram_business_account\":{\"id\":\"200\",\"username\":\"creator\",\"name\":\"IG\"}},{\"id\":\"101\",\"tasks\":[\"MANAGE\"]},{\"id\":\"102\",\"tasks\":[],\"instagram_business_account\":{\"id\":\"202\"}}]}",
                MediaType.APPLICATION_JSON));
    var proof = graph.verify("private-user-token", "INSTAGRAM");
    assertThat(proof.assets()).containsExactly(new MetaDtos.Asset("200", "100", "IG", "creator"));
    server.verify();
  }

  @Test
  void instagramPaginatesAndDeduplicatesOfficialIdsOnTheFixedGraphEndpoint() {
    debugResponse(debug("INSTAGRAM"));
    userAndPermissions();
    server
        .expect(
            requestTo(
                org.hamcrest.Matchers.startsWith("https://graph.facebook.com/v24.0/me/accounts?")))
        .andExpect(
            request ->
                assertThat(
                        java.net.URLDecoder.decode(
                            request.getURI().getRawQuery(),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .contains("fields=id,name,tasks,instagram_business_account{id,username,name}"))
        .andRespond(
            withSuccess(
                """
{"data":[
  {"id":"100","tasks":["MANAGE"],"instagram_business_account":{"id":"200","name":"Business account","username":"business"}},
  {"id":"101","tasks":["MANAGE"]},
  {"id":"102","tasks":[],"instagram_business_account":{"id":"202"}},
  {"id":"103","instagram_business_account":{"id":"203"}}
],"paging":{"next":"https://untrusted.invalid/never-follow","cursors":{"after":"ig-next"}}}
""",
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                org.hamcrest.Matchers.startsWith("https://graph.facebook.com/v24.0/me/accounts?")))
        .andExpect(queryParam("after", "ig-next"))
        .andExpect(
            request ->
                assertThat(
                        java.net.URLDecoder.decode(
                            request.getURI().getRawQuery(),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .contains("fields=id,name,tasks,instagram_business_account{id,username,name}"))
        .andRespond(
            withSuccess(
                """
{"data":[
  {"id":"104","tasks":["MANAGE"],"instagram_business_account":{"id":"200","username":"duplicate"}},
  {"id":"105","tasks":["ANALYZE"],"instagram_business_account":{"id":"201","name":"Creator account","username":"creator"}}
]}
""",
                MediaType.APPLICATION_JSON));
    assertThat(graph.verify("private-user-token", "INSTAGRAM").assets())
        .containsExactly(
            new MetaDtos.Asset("200", "100", "Business account", "business"),
            new MetaDtos.Asset("201", "105", "Creator account", "creator"));
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"debug_missing", "live_missing", "live_declined"})
  void instagramBasicMustBeGrantedInBothDebugAndLivePermissions(String failure) {
    debugResponse(
        failure.equals("debug_missing")
            ? debug("INSTAGRAM").replace("instagram_basic", "other_scope")
            : debug("INSTAGRAM"));
    if (!failure.equals("debug_missing")) {
      String instagram =
          failure.equals("live_declined")
              ? ",{\"permission\":\"instagram_basic\",\"status\":\"declined\"}"
              : "";
      userAndPermissions(
          "{\"data\":[{\"permission\":\"pages_show_list\",\"status\":\"granted\"},"
              + "{\"permission\":\"pages_read_engagement\",\"status\":\"granted\"}"
              + instagram
              + "]}");
    }
    assertThatThrownBy(() -> graph.verify("private-user-token", "INSTAGRAM"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.META_AUTHORIZATION_REJECTED));
    server.verify();
  }

  @Test
  void instagramAcceptsAndRetainsOptionalBusinessManagementScope() {
    debugResponse(
        debug("INSTAGRAM")
            .replace("\"instagram_basic\"", "\"instagram_basic\",\"business_management\""));
    userAndPermissions(
        """
        {"data":[{"permission":"pages_show_list","status":"granted"},
          {"permission":"pages_read_engagement","status":"granted"},
          {"permission":"instagram_basic","status":"granted"},
          {"permission":"business_management","status":"granted"}]}
        """);
    server
        .expect(
            requestTo(
                org.hamcrest.Matchers.startsWith("https://graph.facebook.com/v24.0/me/accounts?")))
        .andRespond(
            withSuccess(
                """
{"data":[{"id":"100","tasks":["MANAGE"],"instagram_business_account":{"id":"200"}}]}
""",
                MediaType.APPLICATION_JSON));
    var proof = graph.verify("private-user-token", "INSTAGRAM");
    assertThat(proof.scopes()).contains("business_management", "instagram_basic");
    assertThat(config().requiredScopes("INSTAGRAM")).doesNotContain("business_management");
    assertThat(proof.assets()).extracting(MetaDtos.Asset::id).containsExactly("200");
    server.verify();
  }

  @Test
  void instagramDoesNotInventAnAccountWhenPagesHaveNoProfessionalLink() {
    debugResponse(debug("INSTAGRAM"));
    userAndPermissions();
    server
        .expect(
            requestTo(
                org.hamcrest.Matchers.startsWith("https://graph.facebook.com/v24.0/me/accounts?")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"id":"100","name":"A Facebook Page","tasks":["MANAGE"]}]}
                """,
                MediaType.APPLICATION_JSON));
    assertThat(graph.verify("private-user-token", "INSTAGRAM").assets()).isEmpty();
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"app", "expired", "invalid", "type", "scopes"})
  void rejectsInvalidTokensBeforeFetchingAnyPages(String reason) {
    String body = debug("FACEBOOK");
    body =
        switch (reason) {
          case "app" -> body.replace("\"app_id\":\"123\"", "\"app_id\":\"999\"");
          case "expired" ->
              body.replace("" + NOW.plusSeconds(3600).getEpochSecond(), "" + NOW.getEpochSecond());
          case "invalid" -> body.replace("true", "false");
          case "type" -> body.replace("USER", "PAGE");
          default -> body.replace("pages_show_list", "other");
        };
    debugResponse(body);
    assertThatThrownBy(() -> graph.verify("private-user-token", "FACEBOOK"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.META_AUTHORIZATION_REJECTED));
    server.verify();
  }

  @Test
  void deniedPermissionIsRejectedEvenIfDebugScopesStillContainIt() {
    debugResponse(debug("FACEBOOK"));
    server
        .expect(requestTo("https://graph.facebook.com/v24.0/me?fields=id"))
        .andRespond(withSuccess("{\"id\":\"99\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://graph.facebook.com/v24.0/me/permissions"))
        .andRespond(
            withSuccess(
                "{\"data\":[{\"permission\":\"pages_show_list\",\"status\":\"declined\"}]}",
                MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> graph.verify("private-user-token", "FACEBOOK"))
        .isInstanceOf(BusinessException.class);
    server.verify();
  }

  @Test
  void timeoutIsSanitizedAndAuthorizationCodeIsNotRetried() {
    server
        .expect(requestTo("https://graph.facebook.com/v24.0/oauth/access_token"))
        .andRespond(
            withException(
                new java.net.SocketTimeoutException(
                    "private-user-token private-app-secret one-time-code")));
    assertThatThrownBy(() -> graph.exchange("one-time-code", "FACEBOOK"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              assertThat(e.getMessage())
                  .doesNotContain("private-user-token", "private-app-secret", "one-time-code");
              assertThat(e.getCause()).isNull();
            });
    server.verify();
  }

  @Test
  void refusesRepeatedOrMissingPagingCursorsWithoutFollowingUrls() {
    debugResponse(debug("FACEBOOK"));
    userAndPermissions();
    server
        .expect(
            requestTo(
                org.hamcrest.Matchers.startsWith("https://graph.facebook.com/v24.0/me/accounts?")))
        .andRespond(
            withSuccess(
                "{\"data\":[],\"paging\":{\"next\":\"https://evil.test\"}}",
                MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> graph.verify("private-user-token", "FACEBOOK"))
        .isInstanceOf(BusinessException.class);
    server.verify();
  }

  @Test
  void payloadUsesAuthenticatedEncryptionAndSeparateKey() {
    var json = new ObjectMapper().registerModule(new JavaTimeModule());
    var cipher = new MetaPayloadCipher(config(), json);
    var proof =
        new MetaDtos.Proof(
            "99", "private-user-token", NOW.plusSeconds(100), Set.of("scope"), List.of());
    String a = cipher.encrypt(proof), b = cipher.encrypt(proof);
    assertThat(a).isNotEqualTo(b).doesNotContain("private-user-token");
    assertThat(cipher.decrypt(a)).isEqualTo(proof);
    byte[] bytes = Base64.getDecoder().decode(a);
    bytes[bytes.length - 1] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes)))
        .isInstanceOf(BusinessException.class);
    assertThat(config().toString()).doesNotContain("private-app-secret", KEY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"disabled", "key", "version", "redirect"})
  void missingOrUnsafeConfigurationReturns503(String reason) {
    var props =
        new MetaOAuthProperties(
            !reason.equals("disabled"),
            "123",
            "secret",
            "456",
            reason.equals("version") ? "latest" : "v24.0",
            reason.equals("redirect")
                ? "http://evil.test/callback"
                : "http://localhost:8080/api/v1/integrations/meta/callback",
            reason.equals("key") ? "short" : KEY);
    assertThatThrownBy(props::requireConfigured)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.META_OAUTH_UNAVAILABLE));
  }
}
