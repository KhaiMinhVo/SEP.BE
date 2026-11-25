package com.influencermatch.backend.creator.client.provider;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.creator.tiktok.TikTokLoginProperties;
import com.influencermatch.backend.exception.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class TikTokLoginProviderTest {
  static final String TOKEN = "https://open.tiktokapis.com/v2/oauth/token/";
  static final String PROFILE =
      "https://open.tiktokapis.com/v2/user/info/?fields=open_id,username,display_name";
  final ObjectMapper json = new ObjectMapper();
  MockRestServiceServer server;
  TikTokSandboxProofProvider provider;

  TikTokLoginProperties config() {
    return new TikTokLoginProperties(
        true,
        "sandbox-key",
        "secret-never-expose",
        "https://test.example/api/v1/integrations/tiktok/callback",
        "user.info.basic,user.info.profile");
  }

  @BeforeEach
  void setup() {
    var builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    provider = new TikTokSandboxProofProvider(config(), builder.build());
  }

  ObjectNode token() {
    return json.createObjectNode()
        .put("access_token", "private-token-" + "x".repeat(300))
        .put("refresh_token", "private-refresh")
        .put("open_id", "app-scoped-id")
        .put("token_type", "Bearer")
        .put("expires_in", 600)
        .put("scope", "user.info.basic,user.info.profile");
  }

  ObjectNode profile() {
    var root = json.createObjectNode();
    root.putObject("error").put("code", "ok");
    root.putObject("data")
        .putObject("user")
        .put("open_id", "app-scoped-id")
        .put("username", "actual_username")
        .put("display_name", "Actual name");
    return root;
  }

  void tokenReply(JsonNode body) {
    server
        .expect(requestTo(TOKEN))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
        .andRespond(withSuccess(body.toString(), MediaType.APPLICATION_JSON));
  }

  void profileReply(JsonNode body) {
    server
        .expect(requestTo(PROFILE))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer private-token-" + "x".repeat(300)))
        .andRespond(withSuccess(body.toString(), MediaType.APPLICATION_JSON));
  }

  @Test
  void sendsTheDocumentedWebTokenFormWithTheDecodedCallbackCode() {
    var code = "opaque+/*!=&%";
    String redirect = java.util.Arrays.stream(URI.create(config().authorizationUrl("state")).getRawQuery().split("&"))
        .filter(param -> param.startsWith("redirect_uri="))
        .map(param -> URLDecoder.decode(param.substring("redirect_uri=".length()), StandardCharsets.UTF_8))
        .findFirst().orElseThrow();
    assertThat(redirect).isEqualTo(config().redirectUri());
    var form = new LinkedMultiValueMap<String, String>();
    form.add("client_key", config().clientKey());
    form.add("client_secret", config().clientSecret());
    form.add("code", code);
    form.add("grant_type", "authorization_code");
    form.add("redirect_uri", redirect);
    server
        .expect(requestTo(TOKEN))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE))
        .andExpect(content().formData(form))
        .andRespond(withSuccess(token().toString(), MediaType.APPLICATION_JSON));
    profileReply(profile());

    assertThat(provider.verify(code).username()).isEqualTo("actual_username");
    server.verify();
  }

  @Test
  void readsRealAccountWithoutLeakingTokensOrUsingExpectedCrawlerIdentity() {
    tokenReply(token());
    profileReply(profile());
    var proof = provider.exchangeAndVerify("real-code", "crawler-id", "wrong_username");
    assertThat(proof.getProviderUserId()).isEqualTo("app-scoped-id");
    assertThat(proof.getProviderUsername()).isEqualTo("actual_username");
    assertThat(proof.getAccessToken()).isNull();
    assertThat(proof.getRefreshToken()).isNull();
    assertThat(proof.toString())
        .doesNotContain("private-token", "private-refresh", "secret-never-expose", "real-code");
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"access_token", "open_id", "token_type", "scope", "expires_in"})
  void rejectsMissingTokenFieldsWithoutFallback(String field) {
    var body = token();
    body.remove(field);
    tokenReply(body);
    assertThatThrownBy(() -> provider.verify("real-code"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.TIKTOK_PROVIDER_ERROR);
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"user.info.basic", "video.list", "", "user.info.profile"})
  void requiresBothGrantedScopes(String scope) {
    tokenReply(token().put("scope", scope));
    assertThatThrownBy(() -> provider.verify("code")).isInstanceOf(BusinessException.class);
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"open_id", "username", "display_name", "mismatched_id", "error_code"})
  void rejectsIncompleteOrContradictoryProfile(String field) {
    tokenReply(token());
    var body = profile();
    var user = (ObjectNode) body.path("data").path("user");
    if (field.equals("mismatched_id")) user.put("open_id", "other-id");
    else if (field.equals("error_code"))
      ((ObjectNode) body.path("error")).put("code", "access_token_invalid");
    else user.remove(field);
    profileReply(body);
    assertThatThrownBy(() -> provider.verify("code"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.TIKTOK_PROVIDER_ERROR);
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 429, 500})
  void sanitizesHttpErrorsWithoutRetry(int status, CapturedOutput output) {
    server
        .expect(requestTo(TOKEN))
        .andRespond(
            withStatus(HttpStatus.valueOf(status))
                .body("secret-never-expose private-token provider-stack")
                .contentType(MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> provider.verify("code"))
        .isInstanceOf(BusinessException.class)
        .hasMessageNotContaining("private-token")
        .hasMessageNotContaining("provider-stack");
    assertThat(output.getAll())
        .contains("hint=unspecified, httpStatus=" + status + ", providerLogId=unavailable")
        .doesNotContain("secret-never-expose", "private-token", "provider-stack");
    server.verify();
  }

  @Test
  void recordsSafeDiagnosticsFromHttpTokenRejection(CapturedOutput output) {
    var body = json.createObjectNode()
        .put("error", "invalid_request")
        .put("error_description", "redirect_uri is not matched; opaque-code secret-never-expose private-token")
        .put("log_id", "202206221854370101130062072500FFA2");
    server.expect(requestTo(TOKEN))
        .andRespond(withBadRequest().body(body.toString()).contentType(MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> provider.verify("opaque-code"))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.TIKTOK_AUTHORIZATION_REJECTED);
    assertThat(output.getAll())
        .contains("invalid_request, hint=redirect_uri, httpStatus=400, providerLogId=202206221854370101130062072500FFA2")
        .doesNotContain("opaque-code", "secret-never-expose", "private-token", "redirect_uri is not matched");
    server.verify();
  }

  @Test
  void sanitizesTimeout() {
    server
        .expect(requestTo(TOKEN))
        .andRespond(withException(new IOException("secret-never-expose")));
    assertThatThrownBy(() -> provider.verify("code"))
        .isInstanceOf(BusinessException.class)
        .hasMessageNotContaining("secret-never-expose");
    server.verify();
  }

  @Test
  void providerDenialDoesNotExposeRawResponse(CapturedOutput output) {
    tokenReply(
        json.createObjectNode()
            .put("error", "access_denied")
            .put("error_description", "private-token")
            .put("log_id", "private-id"));
    assertThatThrownBy(() -> provider.verify("code"))
        .isInstanceOf(BusinessException.class)
        .hasMessageNotContaining("private-token")
        .extracting("code")
        .isEqualTo(ErrorCode.TIKTOK_AUTHORIZATION_REJECTED);
    assertThat(output.getAll())
        .contains("access_denied, hint=unspecified, httpStatus=200, providerLogId=unavailable")
        .doesNotContain("private-token", "private-id");
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "sim:fake:user", "sim-auth-code", "sim-mismatch:code"})
  void mockCodesCannotEnterRealAdapter(String code) {
    assertThatThrownBy(() -> provider.verify(code)).isInstanceOf(BusinessException.class);
    server.verify();
  }

  @Test
  void configurationRequiresExplicitEnableHttpsAndExactScopes() {
    for (var invalid :
        java.util.List.of(
            new TikTokLoginProperties(false, "k", "s", config().redirectUri(), config().scope()),
            new TikTokLoginProperties(
                true, "k", "s", "http://localhost/callback", config().scope()),
            new TikTokLoginProperties(
                true, "k", "s", config().redirectUri(), "user.info.basic,video.list"),
            new TikTokLoginProperties(
                true, "k", "s", config().redirectUri() + "?secret=1", config().scope()))) {
      assertThatThrownBy(invalid::requireConfigured)
          .isInstanceOf(BusinessException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.TIKTOK_OAUTH_UNAVAILABLE);
    }
    assertThat(config().toString()).doesNotContain("secret-never-expose");
    assertThat(config().authorizationUrl("state"))
        .startsWith("https://www.tiktok.com/v2/auth/authorize/")
        .contains(
            "scope=user.info.basic%2Cuser.info.profile",
            "redirect_uri=https%3A%2F%2Ftest.example%2Fapi%2Fv1%2Fintegrations%2Ftiktok%2Fcallback")
        .doesNotContain("video.list", "secret-never-expose");
  }
}
