package com.influencermatch.backend.creator.client.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.creator.client.OAuthProofClient.ProofResult;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OAuthSandboxProviderTest {

  private OAuthSandboxProperties properties;
  private TikTokSandboxProofProvider tikTokProvider;
  private GoogleYouTubeTestingProofProvider googleProvider;
  private SimulatedFallbackProofProvider fallbackProvider;

  @BeforeEach
  void setUp() {
    properties = new OAuthSandboxProperties();
    properties.getTiktok().setClientKey("test_tiktok_client_key");
    properties.getTiktok().setClientSecret("test_tiktok_client_secret");
    properties.getTiktok().setRedirectUri("http://localhost:8080/callback/tiktok");

    properties.getGoogle().setClientId("test_google_client_id");
    properties.getGoogle().setClientSecret("test_google_client_secret");
    properties.getGoogle().setRedirectUri("http://localhost:8080/callback/youtube");

    tikTokProvider = new TikTokSandboxProofProvider(properties);
    googleProvider = new GoogleYouTubeTestingProofProvider(properties);
    fallbackProvider = new SimulatedFallbackProofProvider();
  }

  @Test
  @DisplayName("TikTok provider builds correct Sandbox authorization URL")
  void tikTokBuildsOfficialSandboxUrl() {
    String authUrl = tikTokProvider.buildAuthorizationUrl("token_12345");

    assertThat(authUrl).contains("https://www.tiktok.com/v2/auth/authorize/");
    assertThat(authUrl).contains("client_key=test_tiktok_client_key");
    assertThat(authUrl).contains("response_type=code");
    assertThat(authUrl).contains("state=token_12345");
    assertThat(authUrl).contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fcallback%2Ftiktok");
  }

  @Test
  @DisplayName("Google YouTube provider builds correct Testing authorization URL with channel scope")
  void googleBuildsTestingModeUrl() {
    String authUrl = googleProvider.buildAuthorizationUrl("token_67890");

    assertThat(authUrl).contains("https://accounts.google.com/o/oauth2/v2/auth");
    assertThat(authUrl).contains("client_id=test_google_client_id");
    assertThat(authUrl).contains("response_type=code");
    assertThat(authUrl).contains("access_type=offline");
    assertThat(authUrl).contains("prompt=consent");
    assertThat(authUrl).contains("state=token_67890");
    assertThat(authUrl).contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyoutube.readonly");
  }

  @Test
  @DisplayName("OAuthProofClient delegates to official sandbox when mode is OFFICIAL_SANDBOX")
  void oAuthProofClientDelegatesToOfficialSandbox() {
    properties.setMode("OFFICIAL_SANDBOX");
    MockEnvironment env = new MockEnvironment();
    OAuthProofClient client = new OAuthProofClient(
        env, false, properties, tikTokProvider, googleProvider, fallbackProvider);

    String tikTokUrl = client.buildAuthorizationUrl("TIKTOK", "state_tt");
    assertThat(tikTokUrl).contains("https://www.tiktok.com/v2/auth/authorize/");

    String ytUrl = client.buildAuthorizationUrl("YOUTUBE", "state_yt");
    assertThat(ytUrl).contains("https://accounts.google.com/o/oauth2/v2/auth");
  }

  @Test
  @DisplayName("OAuthProofClient falls back to simulation when mode is MOCK_FALLBACK")
  void oAuthProofClientFallsBackToSimulationInMockMode() {
    properties.setMode("MOCK_FALLBACK");
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("dev");
    OAuthProofClient client = new OAuthProofClient(
        env, true, properties, tikTokProvider, googleProvider, fallbackProvider);

    String fallbackUrl = client.buildAuthorizationUrl("TIKTOK", "state_fallback");
    assertThat(fallbackUrl).startsWith("/api/v1/creators/claim/oauth/tiktok/callback?code=sim-auth-code&state=");

    ProofResult proof = client.exchangeAndVerify("TIKTOK", "sim:custom_user_id:custom_username", null, null);
    assertThat(proof.getProviderUserId()).isEqualTo("custom_user_id");
    assertThat(proof.getProviderUsername()).isEqualTo("custom_username");
  }

  @Test
  @DisplayName("OAuthProofClient handles simulation mismatch codes gracefully")
  void oAuthProofClientHandlesMismatchSimulationCode() {
    properties.setMode("MOCK_FALLBACK");
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("dev");
    OAuthProofClient client = new OAuthProofClient(
        env, true, properties, tikTokProvider, googleProvider, fallbackProvider);

    ProofResult proof = client.exchangeAndVerify("TIKTOK", "sim-mismatch:code", "genuine_id", "genuine_user");
    assertThat(proof.getProviderUserId()).isEqualTo("mismatched_external_id");
    assertThat(proof.getProviderUsername()).isEqualTo("mismatched_user");
  }

  @Test
  @DisplayName("Backwards-compatible constructor preserves existing security profile checks")
  void backwardsCompatibleConstructorPreservesSecurityCheck() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    OAuthProofClient client = new OAuthProofClient(env, true);

    assertThatThrownBy(() -> client.requireConfigured())
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.SOCIAL_PROOF_UNAVAILABLE);
  }
}
