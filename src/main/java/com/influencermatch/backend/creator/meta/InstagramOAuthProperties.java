package com.influencermatch.backend.creator.meta;

import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import java.net.URI;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.UriComponentsBuilder;

@ConfigurationProperties(prefix = "application.instagram")
public record InstagramOAuthProperties(
    boolean enabled,
    String appId,
    String appSecret,
    String graphApiVersion,
    String redirectUri,
    String encryptionKey) {
  public void requireConfigured() {
    try {
      if (!enabled
          || appId == null
          || !appId.matches("\\d+")
          || appSecret == null
          || appSecret.isBlank()
          || graphApiVersion == null
          || !graphApiVersion.matches("v\\d+\\.\\d+")) throw new IllegalArgumentException();
      URI uri = URI.create(redirectUri);
      // Instagram Login deployments use HTTPS, including test tunnels.
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !uri.getPath().endsWith("/integrations/instagram/callback")
          || Base64.getDecoder().decode(encryptionKey).length != 32)
        throw new IllegalArgumentException();
    } catch (Exception error) {
      throw new BusinessException(
          ErrorCode.INSTAGRAM_OAUTH_UNAVAILABLE, "Instagram Login is not configured");
    }
  }

  public String authorizationUrl(String state) {
    requireConfigured();
    return UriComponentsBuilder.fromUriString("https://www.instagram.com/oauth/authorize")
        .queryParam("client_id", appId)
        .queryParam("redirect_uri", redirectUri)
        .queryParam("response_type", "code")
        .queryParam("scope", "instagram_business_basic")
        .queryParam("state", state)
        .build()
        .encode()
        .toUriString();
  }

  @Override
  public String toString() {
    return "InstagramOAuthProperties[enabled=" + enabled + "]";
  }
}
