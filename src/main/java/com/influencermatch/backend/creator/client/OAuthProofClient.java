package com.influencermatch.backend.creator.client;

import com.influencermatch.backend.creator.client.provider.GoogleYouTubeTestingProofProvider;
import com.influencermatch.backend.creator.client.provider.OAuthSandboxProperties;
import com.influencermatch.backend.creator.client.provider.SimulatedFallbackProofProvider;
import com.influencermatch.backend.creator.client.provider.TikTokSandboxProofProvider;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OAuthProofClient {

  private final Environment environment;
  private final boolean mockEnabled;
  private final OAuthSandboxProperties properties;
  private final TikTokSandboxProofProvider tikTokProvider;
  private final GoogleYouTubeTestingProofProvider googleProvider;
  private final SimulatedFallbackProofProvider fallbackProvider;

  @Autowired
  public OAuthProofClient(
      Environment environment,
      @Value("${application.auth.social-proof-mock.enabled:false}") boolean mockEnabled,
      OAuthSandboxProperties properties,
      TikTokSandboxProofProvider tikTokProvider,
      GoogleYouTubeTestingProofProvider googleProvider,
      SimulatedFallbackProofProvider fallbackProvider) {
    this.environment = environment;
    this.mockEnabled = mockEnabled;
    this.properties = properties != null ? properties : new OAuthSandboxProperties();
    this.tikTokProvider = tikTokProvider;
    this.googleProvider = googleProvider;
    this.fallbackProvider =
        fallbackProvider != null ? fallbackProvider : new SimulatedFallbackProofProvider();
  }

  /** Backwards-compatible constructor for existing tests and non-injected environments. */
  public OAuthProofClient(Environment environment, boolean mockEnabled) {
    this(
        environment,
        mockEnabled,
        new OAuthSandboxProperties(),
        null,
        null,
        new SimulatedFallbackProofProvider());
  }

  public boolean isOfficialSandboxMode() {
    return properties != null && properties.isOfficialSandboxMode();
  }

  public void requireConfigured() {
    if (isOfficialSandboxMode()) {
      return;
    }
    boolean devMock = mockEnabled && environment.acceptsProfiles(Profiles.of("dev & !prod"));
    if (!devMock) {
      throw new BusinessException(
          ErrorCode.SOCIAL_PROOF_UNAVAILABLE, "Real provider verification is not configured");
    }
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ProofResult {
    private String providerUserId;
    private String providerUsername;
    private String accessToken;
    private String refreshToken;
    private LocalDateTime tokenExpiresAt;
    private Map<String, Object> rawProfileData;
  }

  /** Generates authorization redirect URL for official provider sandbox or fallback mock. */
  public String buildAuthorizationUrl(String platform, String state) {
    com.influencermatch.backend.creator.meta.MetaPlatformGuard.requireLegacy(platform);
    requireConfigured();
    String normPlatform = platform != null ? platform.toUpperCase() : "";

    if (isOfficialSandboxMode()) {
      if ("TIKTOK".equals(normPlatform)
          && tikTokProvider != null
          && tikTokProvider.isConfigured()) {
        log.info("Directing client to Official TikTok Developer Sandbox consent screen");
        return tikTokProvider.buildAuthorizationUrl(state);
      }
      if (("YOUTUBE".equals(normPlatform) || "GOOGLE".equals(normPlatform))
          && googleProvider != null
          && googleProvider.isConfigured()) {
        log.info("Directing client to Google OAuth Testing consent screen");
        return googleProvider.buildAuthorizationUrl(state);
      }
    }

    requireDevSimulation();
    log.info("Directing client to Simulated OAuth Fallback for platform: {}", normPlatform);
    return fallbackProvider.buildAuthorizationUrl(platform, state);
  }

  /** Exchanges authorization code with the provider and verifies account identity. */
  public ProofResult exchangeAndVerify(
      String platform, String code, String expectedExternalId, String expectedUserName) {
    com.influencermatch.backend.creator.meta.MetaPlatformGuard.requireLegacy(platform);
    requireConfigured();
    String normPlatform = platform != null ? platform.toUpperCase() : "";

    // Explicit simulation codes always route to SimulatedFallbackProofProvider
    if (code != null
        && (code.startsWith("sim:")
            || code.startsWith("sim-mismatch")
            || "sim-auth-code".equals(code))) {
      requireDevSimulation();
      return fallbackProvider.exchangeAndVerify(
          platform, code, expectedExternalId, expectedUserName);
    }

    if (isOfficialSandboxMode()) {
      if ("TIKTOK".equals(normPlatform)
          && tikTokProvider != null
          && tikTokProvider.isConfigured()) {
        return tikTokProvider.exchangeAndVerify(code, expectedExternalId, expectedUserName);
      }
      if (("YOUTUBE".equals(normPlatform) || "GOOGLE".equals(normPlatform))
          && googleProvider != null
          && googleProvider.isConfigured()) {
        return googleProvider.exchangeAndVerify(code, expectedExternalId, expectedUserName);
      }
    }

    requireDevSimulation();
    return fallbackProvider.exchangeAndVerify(platform, code, expectedExternalId, expectedUserName);
  }

  private void requireDevSimulation() {
    if (!mockEnabled || !environment.acceptsProfiles(Profiles.of("dev & !prod"))) {
      throw new BusinessException(
          ErrorCode.SOCIAL_PROOF_UNAVAILABLE,
          "Simulated provider verification is only available in explicitly enabled development"
              + " mode");
    }
  }
}
