package com.influencermatch.backend.creator.client.provider;

import com.influencermatch.backend.creator.client.OAuthProofClient.ProofResult;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class GoogleYouTubeTestingProofProvider implements OAuthProofProvider {

  private final OAuthSandboxProperties properties;
  private final RestClient restClient;

  public GoogleYouTubeTestingProofProvider(OAuthSandboxProperties properties) {
    this.properties = properties;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String getPlatform() {
    return "YOUTUBE";
  }

  @Override
  public boolean isConfigured() {
    return properties != null && properties.getGoogle().isConfigured();
  }

  @Override
  public String buildAuthorizationUrl(String state) {
    var config = properties.getGoogle();
    String authBase = config.getAuthUrl() != null && !config.getAuthUrl().isBlank()
        ? config.getAuthUrl()
        : "https://accounts.google.com/o/oauth2/v2/auth";

    String scope = config.getScope() != null && !config.getScope().isBlank()
        ? config.getScope()
        : "https://www.googleapis.com/auth/youtube.readonly";

    String encodedRedirect = URLEncoder.encode(config.getRedirectUri(), StandardCharsets.UTF_8);
    String encodedScope = URLEncoder.encode(scope, StandardCharsets.UTF_8);
    String encodedState = state != null ? URLEncoder.encode(state, StandardCharsets.UTF_8) : "";

    return String.format(
        "%s?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&access_type=offline&prompt=consent&state=%s",
        authBase.endsWith("?") ? authBase.substring(0, authBase.length() - 1) : authBase,
        config.resolveClientId(),
        encodedRedirect,
        encodedScope,
        encodedState);
  }

  @Override
  @SuppressWarnings("unchecked")
  public ProofResult exchangeAndVerify(String code, String expectedExternalId, String expectedUserName) {
    var config = properties.getGoogle();
    if (!isConfigured()) {
      throw new BusinessException(
          ErrorCode.SOCIAL_PROOF_UNAVAILABLE, "Google OAuth Testing credentials are not configured");
    }

    try {
      // 1. Exchange code for access_token and refresh_token
      String tokenUrl = config.getTokenUrl() != null && !config.getTokenUrl().isBlank()
          ? config.getTokenUrl()
          : "https://oauth2.googleapis.com/token";

      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("client_id", config.resolveClientId());
      form.add("client_secret", config.getClientSecret());
      form.add("code", code);
      form.add("grant_type", "authorization_code");
      form.add("redirect_uri", config.getRedirectUri());

      Map<String, Object> tokenResponse = restClient.post()
          .uri(tokenUrl)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .body(Map.class);

      if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
        log.error("Google token exchange failed: {}", tokenResponse);
        throw new BusinessException(ErrorCode.SOCIAL_PROOF_UNAVAILABLE, "Google OAuth authorization failed: token not granted");
      }

      String accessToken = (String) tokenResponse.get("access_token");
      String refreshToken = (String) tokenResponse.getOrDefault("refresh_token", "");
      Number expiresIn = (Number) tokenResponse.getOrDefault("expires_in", 3600);

      // 2. Query YouTube Data API v3 to retrieve the authentic Channel ID (UC...)
      String youtubeUrl = config.getYoutubeChannelUrl() != null && !config.getYoutubeChannelUrl().isBlank()
          ? config.getYoutubeChannelUrl()
          : "https://www.googleapis.com/youtube/v3/channels";

      String queryUri = youtubeUrl + (youtubeUrl.contains("?") ? "&" : "?") + "part=id,snippet&mine=true";

      Map<String, Object> channelResponse = restClient.get()
          .uri(queryUri)
          .header("Authorization", "Bearer " + accessToken)
          .retrieve()
          .body(Map.class);

      String channelId = "";
      String channelTitle = "";
      String customUrl = "";

      if (channelResponse != null && channelResponse.containsKey("items")) {
        List<?> items = (List<?>) channelResponse.get("items");
        if (items != null && !items.isEmpty() && items.get(0) instanceof Map) {
          Map<String, Object> firstItem = (Map<String, Object>) items.get(0);
          channelId = (String) firstItem.getOrDefault("id", "");

          if (firstItem.containsKey("snippet") && firstItem.get("snippet") instanceof Map) {
            Map<String, Object> snippet = (Map<String, Object>) firstItem.get("snippet");
            channelTitle = (String) snippet.getOrDefault("title", "");
            customUrl = (String) snippet.getOrDefault("customUrl", "");
          }
        }
      }

      String providerUsername = customUrl != null && !customUrl.isBlank()
          ? customUrl.replace("@", "").trim()
          : (channelTitle != null && !channelTitle.isBlank() ? channelTitle : expectedUserName);

      Map<String, Object> rawData = new HashMap<>();
      rawData.put("platform", "YOUTUBE");
      rawData.put("verificationEnvironment", "TESTING_MODE");
      rawData.put("channelId", channelId);
      rawData.put("channelTitle", channelTitle);
      rawData.put("customUrl", customUrl);
      rawData.put("rawResponse", channelResponse);

      return ProofResult.builder()
          .providerUserId(channelId != null && !channelId.isBlank() ? channelId : expectedExternalId)
          .providerUsername(providerUsername)
          .accessToken(accessToken)
          .refreshToken(refreshToken)
          .tokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn.longValue()))
          .rawProfileData(rawData)
          .build();

    } catch (BusinessException be) {
      throw be;
    } catch (Exception ex) {
      log.error("Error communicating with Google OAuth / YouTube API: {}", ex.getMessage(), ex);
      throw new BusinessException(
          ErrorCode.SOCIAL_PROOF_UNAVAILABLE, "Error communicating with Google OAuth / YouTube API: " + ex.getMessage());
    }
  }
}
