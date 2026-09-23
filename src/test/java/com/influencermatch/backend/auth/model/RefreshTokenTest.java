package com.influencermatch.backend.auth.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.influencermatch.backend.auth.model.RefreshToken;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

  @Test
  @DisplayName("Refresh Token Status - Should be active only when not expired and not revoked")
  void activeOnlyWhenNotExpiredOrRevoked() {
    // GIVEN
    LocalDateTime now = LocalDateTime.now();
    RefreshToken token = RefreshToken.builder().expiresAt(now.plusMinutes(1)).build();

    // WHEN & THEN: Initial state is active
    assertThat(token.active(now)).isTrue();

    // WHEN & THEN: Revoked token is inactive
    token.setRevokedAt(now);
    assertThat(token.active(now)).isFalse();

    // WHEN & THEN: Expired token is inactive
    token.setRevokedAt(null);
    token.setExpiresAt(now.minusSeconds(1));
    assertThat(token.active(now)).isFalse();
  }
}
