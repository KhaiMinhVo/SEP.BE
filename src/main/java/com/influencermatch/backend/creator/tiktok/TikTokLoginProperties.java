package com.influencermatch.backend.creator.tiktok;

import com.influencermatch.backend.exception.*;
import java.net.URI;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.UriComponentsBuilder;

@ConfigurationProperties("application.tiktok")
public record TikTokLoginProperties(
    boolean enabled, String clientKey, String clientSecret, String redirectUri, String scope) {
  public static final Set<String> SCOPES = Set.of("user.info.basic", "user.info.profile");

  public void requireConfigured() {
    try {
      if (!enabled
          || clientKey == null
          || clientKey.isBlank()
          || clientKey.length() > 255
          || clientSecret == null
          || clientSecret.isBlank()) throw new IllegalArgumentException();
      URI uri = URI.create(redirectUri);
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !uri.getPath().endsWith("/integrations/tiktok/callback")
          || scope == null
          || !new HashSet<>(Arrays.asList(scope.split(","))).equals(SCOPES))
        throw new IllegalArgumentException();
    } catch (Exception ex) {
      throw new BusinessException(
          ErrorCode.TIKTOK_OAUTH_UNAVAILABLE, "TikTok Sandbox Login is not configured");
    }
  }

  public String authorizationUrl(String state) {
    requireConfigured();
    return UriComponentsBuilder.fromUriString("https://www.tiktok.com/v2/auth/authorize/")
        .queryParam("client_key", clientKey)
        .queryParam("response_type", "code")
        .queryParam("scope", "user.info.basic,user.info.profile")
        .queryParam("redirect_uri", redirectUri)
        .queryParam("state", state)
        .build()
        .encode()
        .toUriString();
  }

  @Override
  public String toString() {
    return "TikTokLoginProperties[enabled=" + enabled + "]";
  }
}
