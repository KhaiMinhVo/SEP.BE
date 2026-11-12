package com.influencermatch.backend.auth.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

  @Test
  @DisplayName("Refresh Token Status - Should be active only when not expired and not revoked")
  void activeOnlyWhenNotExpiredOrRevoked() {
    // GIVEN
    Instant now = Instant.now();
    RefreshToken token = RefreshToken.builder().expiresAt(now.plusSeconds(60)).build();

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
