package com.influencermatch.backend.creator.meta;

import com.influencermatch.backend.exception.*;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@ConfigurationProperties(prefix = "application.meta")
public record MetaOAuthProperties(
    boolean enabled,
    String appId,
    String appSecret,
    String loginConfigId,
    String instagramPageLoginConfigId,
    String graphApiVersion,
    String redirectUri,
    String encryptionKey) {
  @ConstructorBinding
  public MetaOAuthProperties {}

  public MetaOAuthProperties(
      boolean enabled,
      String appId,
      String appSecret,
      String loginConfigId,
      String graphApiVersion,
      String redirectUri,
      String encryptionKey) {
    this(
        enabled,
        appId,
        appSecret,
        loginConfigId,
        null,
        graphApiVersion,
        redirectUri,
        encryptionKey);
  }

  public void requireConfigured() {
    requireConfigured("FACEBOOK");
  }

  public void requireConfigured(String platform) {
    try {
      requireApplicationConfigured();
      if ("INSTAGRAM".equals(platform)
          && (!StringUtils.hasText(instagramPageLoginConfigId)
              || !instagramPageLoginConfigId.matches("\\d+")))
        throw new IllegalArgumentException();
    } catch (Exception error) {
      throw new BusinessException(ErrorCode.META_OAUTH_UNAVAILABLE, "Meta OAuth is not configured");
    }
  }

  public void requireApplicationConfigured() {
    try {
      if (!enabled
          || !StringUtils.hasText(appId)
          || !appId.matches("\\d+")
          || !StringUtils.hasText(appSecret)
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
    return authorizationUrl(state, "FACEBOOK");
  }

  public String authorizationUrl(String state, String platform) {
    requireConfigured(platform);
    String configId =
        "INSTAGRAM".equals(platform) ? instagramPageLoginConfigId : loginConfigId;
    var builder =
        UriComponentsBuilder.fromUriString(
                "https://www.facebook.com/" + graphApiVersion + "/dialog/oauth")
            .queryParam("client_id", appId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("response_type", "code")
            .queryParam("state", state);
    if (StringUtils.hasText(configId)) {
      if (!configId.matches("\\d+"))
        throw new BusinessException(
            ErrorCode.META_OAUTH_UNAVAILABLE, "Meta OAuth configuration ID is invalid");
      builder.queryParam("config_id", configId);
    } else if ("FACEBOOK".equals(platform)) {
      builder.queryParam("scope", String.join(",", requiredScopes(platform)));
    }
    return builder.build().encode().toUriString();
  }

  public Set<String> requiredScopes(String platform) {
    return platform.equals("INSTAGRAM")
        ? Set.of("pages_show_list", "pages_read_engagement", "instagram_basic")
        : Set.of("pages_show_list");
  }

  // Avoid record's default toString leaking app secret or encryption key.
  @Override
  public String toString() {
    return "MetaOAuthProperties[enabled=" + enabled + "]";
  }
}
