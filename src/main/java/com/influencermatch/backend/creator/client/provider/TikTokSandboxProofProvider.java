package com.influencermatch.backend.creator.client.provider;

import com.influencermatch.backend.creator.client.OAuthProofClient.ProofResult;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
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
public class TikTokSandboxProofProvider implements OAuthProofProvider {

  private final OAuthSandboxProperties properties;
  private final RestClient restClient;

  public TikTokSandboxProofProvider(OAuthSandboxProperties properties) {
    this.properties = properties;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String getPlatform() {
    return "TIKTOK";
  }

  @Override
  public boolean isConfigured() {
    return properties != null && properties.getTiktok().isConfigured();
  }

  @Override
  public String buildAuthorizationUrl(String state) {
    var config = properties.getTiktok();
    String authBase = config.getAuthUrl() != null && !config.getAuthUrl().isBlank()
        ? config.getAuthUrl()
        : "https://www.tiktok.com/v2/auth/authorize/";

    String scope = config.getScope() != null && !config.getScope().isBlank()
        ? config.getScope()
        : "user.info.basic,video.list";

    String encodedRedirect = URLEncoder.encode(config.getRedirectUri(), StandardCharsets.UTF_8);
    String encodedScope = URLEncoder.encode(scope, StandardCharsets.UTF_8);
    String encodedState = state != null ? URLEncoder.encode(state, StandardCharsets.UTF_8) : "";

    return String.format(
        "%s?client_key=%s&response_type=code&scope=%s&redirect_uri=%s&state=%s",
        authBase.endsWith("?") ? authBase.substring(0, authBase.length() - 1) : authBase,
        config.resolveClientId(),
        encodedScope,
        encodedRedirect,
        encodedState);
  }

  @Override
  @SuppressWarnings("unchecked")
  public ProofResult exchangeAndVerify(String code, String expectedExternalId, String expectedUserName) {
    var config = properties.getTiktok();
    if (!isConfigured()) {
      throw new BusinessException(
          ErrorCode.SOCIAL_PROOF_UNAVAILABLE, "TikTok Developer Sandbox credentials are not configured");
    }

    try {
      // 1. Exchange code for access_token
      String tokenUrl = config.getTokenUrl() != null && !config.getTokenUrl().isBlank()
          ? config.getTokenUrl()
          : "https://open.tiktokapis.com/v2/oauth/token/";

      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("client_key", config.resolveClientId());
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

      if (tokenResponse == null) {
        throw new BusinessException(ErrorCode.SOCIAL_PROOF_UNAVAILABLE, "Empty response from TikTok token endpoint");
      }

      Map<String, Object> data = tokenResponse.containsKey("data") && tokenResponse.get("data") instanceof Map
          ? (Map<String, Object>) tokenResponse.get("data")
          : tokenResponse;

      String accessToken = (String) data.get("access_token");
      String refreshToken = (String) data.getOrDefault("refresh_token", "");
      String openId = (String) data.getOrDefault("open_id", "");
      Number expiresIn = (Number) data.getOrDefault("expires_in", 86400);

      if (accessToken == null || accessToken.isBlank()) {
        Object error = tokenResponse.get("error");
        log.error("TikTok token exchange failed: {}", error != null ? error : tokenResponse);
        throw new BusinessException(ErrorCode.SOCIAL_PROOF_UNAVAILABLE, "TikTok OAuth authorization failed: token not granted");
      }

      // 2. Fetch User Info to get canonical username and profile details
      String userInfoUrl = config.getUserInfoUrl() != null && !config.getUserInfoUrl().isBlank()
          ? config.getUserInfoUrl()
          : "https://open.tiktokapis.com/v2/user/info/?fields=open_id,union_id,avatar_url,display_name,username";

      Map<String, Object> userResponse = restClient.get()
          .uri(userInfoUrl)
          .header("Authorization", "Bearer " + accessToken)
          .retrieve()
          .body(Map.class);

      String resolvedUsername = "";
      String displayName = "";
      String avatarUrl = "";

      if (userResponse != null && userResponse.containsKey("data") && userResponse.get("data") instanceof Map) {
        Map<String, Object> userData = (Map<String, Object>) userResponse.get("data");
        if (userData.containsKey("user") && userData.get("user") instanceof Map) {
          Map<String, Object> user = (Map<String, Object>) userData.get("user");
          resolvedUsername = (String) user.getOrDefault("username", "");
          displayName = (String) user.getOrDefault("display_name", "");
          avatarUrl = (String) user.getOrDefault("avatar_url", "");
          if (user.containsKey("open_id") && (openId == null || openId.isBlank())) {
            openId = (String) user.get("open_id");
          }
        }
      }

      // Normalize username: remove leading @, trim
      if (resolvedUsername != null) {
        resolvedUsername = resolvedUsername.replace("@", "").trim();
      }

      Map<String, Object> rawData = new HashMap<>();
      rawData.put("platform", "TIKTOK");
      rawData.put("verificationEnvironment", "OFFICIAL_SANDBOX");
      rawData.put("openId", openId);
      rawData.put("username", resolvedUsername);
      rawData.put("displayName", displayName);
      rawData.put("avatarUrl", avatarUrl);
      rawData.put("tokenData", data);

      return ProofResult.builder()
          .providerUserId(openId != null && !openId.isBlank() ? openId : expectedExternalId)
          .providerUsername(resolvedUsername != null && !resolvedUsername.isBlank() ? resolvedUsername : expectedUserName)
          .accessToken(accessToken)
          .refreshToken(refreshToken)
          .tokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn.longValue()))
          .rawProfileData(rawData)
          .build();

    } catch (BusinessException be) {
      throw be;
    } catch (Exception ex) {
      log.error("Error communicating with TikTok Developer Sandbox: {}", ex.getMessage(), ex);
      throw new BusinessException(
          ErrorCode.SOCIAL_PROOF_UNAVAILABLE, "Error communicating with TikTok Sandbox: " + ex.getMessage());
    }
  }
}
