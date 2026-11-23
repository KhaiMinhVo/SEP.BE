package com.influencermatch.backend.creator.meta;

import com.influencermatch.backend.exception.*;
import java.net.URI;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@ConfigurationProperties(prefix = "application.instagram-unified")
public record InstagramUnifiedOAuthProperties(
    boolean enabled,
    String appId,
    String appSecret,
    String loginConfigId,
    String graphApiVersion,
    String redirectUri,
    String encryptionKey) {
  @ConstructorBinding
  public InstagramUnifiedOAuthProperties {}

  public void requireConfigured() {
    try {
      if (!enabled
          || !StringUtils.hasText(appId)
          || !appId.matches("\\d+")
          || !StringUtils.hasText(appSecret)
          || !StringUtils.hasText(loginConfigId)
          || !loginConfigId.matches("\\d+")
          || !StringUtils.hasText(graphApiVersion)
          || !graphApiVersion.matches("v\\d+\\.\\d+"))
        throw new IllegalArgumentException();
      URI uri = URI.create(redirectUri);
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !uri.getPath().endsWith("/integrations/instagram-unified/callback")
          || Base64.getDecoder().decode(encryptionKey).length != 32)
        throw new IllegalArgumentException();
    } catch (Exception error) {
      throw new BusinessException(
          ErrorCode.INSTAGRAM_OAUTH_UNAVAILABLE,
          "Instagram Unified Login is not configured");
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
        .queryParam("prompt", "login")
        .queryParam("state", state)
        .build()
        .encode()
        .toUriString();
  }

  @Override
  public String toString() {
    return "InstagramUnifiedOAuthProperties[enabled=" + enabled + "]";
  }
}
