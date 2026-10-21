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
