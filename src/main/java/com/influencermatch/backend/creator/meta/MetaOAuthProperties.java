package com.influencermatch.backend.creator.meta;

import com.influencermatch.backend.exception.*;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@ConfigurationProperties(prefix = "application.meta")
public record MetaOAuthProperties(
    boolean enabled,
    String appId,
    String appSecret,
    String loginConfigId,
    String graphApiVersion,
    String redirectUri,
    String encryptionKey) {
  public void requireConfigured() {
    try {
      if (!enabled
          || !StringUtils.hasText(appId)
          || !appId.matches("\\d+")
          || !StringUtils.hasText(appSecret)
          || !StringUtils.hasText(loginConfigId)
          || !loginConfigId.matches("\\d+")
          || !StringUtils.hasText(graphApiVersion)
          || !graphApiVersion.matches("v\\d+\\.\\d+")) throw new IllegalArgumentException();
      URI uri = URI.create(redirectUri);
      boolean secure = "https".equals(uri.getScheme());
      boolean loopback =
          "http".equals(uri.getScheme())
              && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
      if ((!secure && !loopback)
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !uri.getPath().endsWith("/integrations/meta/callback"))
        throw new IllegalArgumentException();
      if (java.util.Base64.getDecoder().decode(encryptionKey).length != 32)
        throw new IllegalArgumentException();
    } catch (Exception error) {
      throw new BusinessException(ErrorCode.META_OAUTH_UNAVAILABLE, "Meta OAuth is not configured");
    }
  }

  public String authorizationUrl(String state) {
    requireConfigured();
    return UriComponentsBuilder.fromUriString(
            "https://www.facebook.com/" + graphApiVersion + "/dialog/oauth")
        .queryParam("client_id", appId)
        .queryParam("redirect_uri", redirectUri)
        .queryParam("config_id", loginConfigId)
        .queryParam("response_type", "code")
        .queryParam("state", state)
        .build()
        .encode()
        .toUriString();
  }

  public Set<String> requiredScopes(String platform) {
    return platform.equals("INSTAGRAM")
        ? Set.of("pages_show_list", "pages_read_engagement", "instagram_basic")
        : Set.of("pages_show_list", "pages_read_engagement");
  }

  // Avoid record's default toString leaking app secret or encryption key.
  @Override
  public String toString() {
    return "MetaOAuthProperties[enabled=" + enabled + "]";
  }
}
