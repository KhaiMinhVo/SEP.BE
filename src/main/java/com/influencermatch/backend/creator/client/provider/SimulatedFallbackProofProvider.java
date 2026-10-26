package com.influencermatch.backend.creator.client.provider;

import com.influencermatch.backend.creator.client.OAuthProofClient.ProofResult;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SimulatedFallbackProofProvider {

  public String buildAuthorizationUrl(String platform, String state) {
    String encodedState = state != null ? URLEncoder.encode(state, StandardCharsets.UTF_8) : "";
    return "/api/v1/creators/claim/oauth/" + (platform != null ? platform.toLowerCase() : "tiktok")
        + "/callback?code=sim-auth-code&state=" + encodedState;
  }

  public ProofResult exchangeAndVerify(
      String platform, String code, String expectedExternalId, String expectedUserName) {

    if (code != null && code.startsWith("sim-mismatch")) {
      return ProofResult.builder()
          .providerUserId("mismatched_external_id")
          .providerUsername("mismatched_user")
          .accessToken("sim_access_token_invalid")
          .refreshToken("sim_refresh_token_invalid")
          .tokenExpiresAt(LocalDateTime.now().plusHours(1))
          .rawProfileData(Map.of(
              "simulated", true,
              "verificationEnvironment", "SIMULATION",
              "channelId", "mismatched_external_id"))
          .build();
    }

    String resolvedUserId = expectedExternalId != null ? expectedExternalId : "provider_id_" + code;
    String resolvedUsername = expectedUserName != null ? expectedUserName : "provider_user_" + code;

    if (code != null && code.startsWith("sim:")) {
      String[] parts = code.split(":");
      if (parts.length >= 2 && !parts[1].isBlank()) {
        resolvedUserId = parts[1].trim();
      }
      if (parts.length >= 3 && !parts[2].isBlank()) {
        resolvedUsername = parts[2].trim();
      }
    }

    return ProofResult.builder()
        .providerUserId(resolvedUserId)
        .providerUsername(resolvedUsername)
        .accessToken("access_token_" + code)
        .refreshToken("refresh_token_" + code)
        .tokenExpiresAt(LocalDateTime.now().plusHours(2))
        .rawProfileData(Map.of(
            "platform", platform != null ? platform : "UNKNOWN",
            "externalId", resolvedUserId,
            "userName", resolvedUsername,
            "simulated", true,
            "verificationEnvironment", "SIMULATION"))
        .build();
  }
}
