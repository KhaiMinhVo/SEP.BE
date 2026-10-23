package com.influencermatch.backend.creator.client;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

@Component
public class OAuthProofClient {
  private final boolean enabled;
  public OAuthProofClient(org.springframework.core.env.Environment environment,
      @org.springframework.beans.factory.annotation.Value("${application.auth.social-proof-mock.enabled:false}") boolean enabled) {
    this.enabled=enabled && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev & !prod"));
  }


  public void requireConfigured() {
    if (!enabled) throw new com.influencermatch.backend.exception.BusinessException(
        com.influencermatch.backend.exception.ErrorCode.SOCIAL_PROOF_UNAVAILABLE, "Real provider verification is not configured");
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

  public ProofResult exchangeAndVerify(
      String platform, String code, String expectedExternalId, String expectedUserName) {
    requireConfigured();
    if (code != null && code.startsWith("sim-mismatch")) {
      return ProofResult.builder()
          .providerUserId("mismatched_external_id")
          .providerUsername("mismatched_user")
          .accessToken("sim_access_token_invalid")
          .refreshToken("sim_refresh_token_invalid")
          .tokenExpiresAt(LocalDateTime.now().plusHours(1))
          .rawProfileData(Map.of("simulated", true, "channelId", "mismatched_external_id"))
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
        .rawProfileData(Map.of("platform", platform, "externalId", resolvedUserId, "userName", resolvedUsername))
        .build();
  }
}
