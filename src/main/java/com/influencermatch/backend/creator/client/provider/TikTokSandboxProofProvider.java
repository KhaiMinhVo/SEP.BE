package com.influencermatch.backend.creator.client.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.creator.client.OAuthProofClient.ProofResult;
import com.influencermatch.backend.creator.tiktok.TikTokLoginProperties;
import com.influencermatch.backend.exception.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

@Component
public class TikTokSandboxProofProvider implements OAuthProofProvider {
  public record VerifiedAccount(String openId, String username, String displayName) {
    @Override
    public String toString() {
      return "TikTokAccount[redacted]";
    }
  }

  private final TikTokLoginProperties config;
  private final RestClient http;

  @Autowired
  public TikTokSandboxProofProvider(TikTokLoginProperties config) {
    this(config, client());
  }

  TikTokSandboxProofProvider(TikTokLoginProperties config, RestClient http) {
    this.config = config;
    this.http = http;
  }

  public TikTokSandboxProofProvider(OAuthSandboxProperties properties) {
    this(
        new TikTokLoginProperties(
            true,
            properties.getTiktok().resolveClientId(),
            properties.getTiktok().getClientSecret(),
            properties.getTiktok().getRedirectUri(),
            "user.info.basic,user.info.profile"));
  }

  private static RestClient client() {
    var factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(java.net.HttpURLConnection connection, String method)
              throws java.io.IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
          }
        };
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String getPlatform() {
    return "TIKTOK";
  }

  @Override
  public boolean isConfigured() {
    try {
      config.requireConfigured();
      return true;
    } catch (BusinessException ex) {
      return false;
    }
  }

  @Override
  public String buildAuthorizationUrl(String state) {
    return config.authorizationUrl(state);
  }

  private BusinessException invalid() {
    return new BusinessException(
        ErrorCode.TIKTOK_PROVIDER_ERROR, "TikTok returned an invalid account or token response");
  }

  private String text(JsonNode node, String field, int max) {
    var value = node.path(field);
    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max)
      throw invalid();
    return value.asText();
  }

  public VerifiedAccount verify(String code) {
    config.requireConfigured();
    if (code == null
        || code.isBlank()
        || code.length() > 4096
        || (code.startsWith("sim:")
            || code.startsWith("sim-mismatch")
            || code.equals("sim-auth-code")))
      throw new BusinessException(
          ErrorCode.TIKTOK_AUTHORIZATION_REJECTED, "TikTok authorization code is required");
    try {
      var form = new LinkedMultiValueMap<String, String>();
      form.add("client_key", config.clientKey());
      form.add("client_secret", config.clientSecret());
      form.add("code", code);
      form.add("grant_type", "authorization_code");
      form.add("redirect_uri", config.redirectUri());
      var token =
          http.post()
              .uri("https://open.tiktokapis.com/v2/oauth/token/")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
      if (token == null || !token.isObject()) throw invalid();
      if (token.has("error"))
        throw new BusinessException(
            ErrorCode.TIKTOK_AUTHORIZATION_REJECTED, "TikTok did not grant authorization");
      String accessToken = text(token, "access_token", 8192), openId = text(token, "open_id", 255);
      if (!"Bearer".equalsIgnoreCase(text(token, "token_type", 32))
          || !new HashSet<>(Arrays.asList(text(token, "scope", 2048).split(",")))
              .containsAll(TikTokLoginProperties.SCOPES)
          || !token.path("expires_in").isIntegralNumber()
          || !token.path("expires_in").canConvertToLong()
          || token.path("expires_in").asLong() <= 0) throw invalid();
      var profile =
          http.get()
              .uri("https://open.tiktokapis.com/v2/user/info/?fields=open_id,username,display_name")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
              .retrieve()
              .body(JsonNode.class);
      if (profile == null || !"ok".equals(profile.path("error").path("code").asText()))
        throw invalid();
      var user = profile.path("data").path("user");
      if (!openId.equals(text(user, "open_id", 255))) throw invalid();
      return new VerifiedAccount(
          openId, text(user, "username", 255), text(user, "display_name", 255));
    } catch (BusinessException ex) {
      throw ex;
    } catch (RestClientResponseException ex) {
      if (Set.of(400, 401, 403).contains(ex.getStatusCode().value()))
        throw new BusinessException(
            ErrorCode.TIKTOK_AUTHORIZATION_REJECTED, "TikTok rejected authorization");
      throw new BusinessException(ErrorCode.TIKTOK_PROVIDER_ERROR, "TikTok request failed");
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.TIKTOK_PROVIDER_ERROR, "TikTok request failed");
    }
  }

  @Override
  public ProofResult exchangeAndVerify(
      String code, String expectedExternalId, String expectedUserName) {
    var account = verify(code);
    return ProofResult.builder()
        .providerUserId(account.openId())
        .providerUsername(account.username())
        .rawProfileData(Map.of("verificationEnvironment", "OFFICIAL_SANDBOX"))
        .build();
  }
}
