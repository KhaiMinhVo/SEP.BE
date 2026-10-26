package com.influencermatch.backend.creator.client.provider;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "application.oauth")
public class OAuthSandboxProperties {

  /**
   * Verification mode:
   * MOCK_FALLBACK: Isolated offline simulation with test tokens (sim:*, sim-mismatch*)
   * OFFICIAL_SANDBOX: Official TikTok Login Kit Sandbox & Google OAuth Testing
   */
  private String mode = "MOCK_FALLBACK";

  private ProviderConfig tiktok = new ProviderConfig();
  private ProviderConfig google = new ProviderConfig();

  @Data
  public static class ProviderConfig {
    private String clientKey;
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String authUrl;
    private String tokenUrl;
    private String userInfoUrl;
    private String youtubeChannelUrl;
    private String scope;

    public String resolveClientId() {
      return clientKey != null && !clientKey.isBlank() ? clientKey.trim() : (clientId != null ? clientId.trim() : "");
    }

    public boolean isConfigured() {
      String id = resolveClientId();
      return !id.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
  }

  public boolean isOfficialSandboxMode() {
    return "OFFICIAL_SANDBOX".equalsIgnoreCase(mode);
  }
}
