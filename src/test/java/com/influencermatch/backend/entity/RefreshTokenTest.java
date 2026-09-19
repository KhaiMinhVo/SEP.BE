package com.influencermatch.backend.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.influencermatch.backend.auth.model.RefreshToken;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {
  @Test
  void activeOnlyWhenNotExpiredOrRevoked() {
    LocalDateTime now = LocalDateTime.now();
    RefreshToken token = RefreshToken.builder().expiresAt(now.plusMinutes(1)).build();
    assertThat(token.active(now)).isTrue();
    token.setRevokedAt(now);
    assertThat(token.active(now)).isFalse();
    token.setRevokedAt(null);
    token.setExpiresAt(now.minusSeconds(1));
    assertThat(token.active(now)).isFalse();
  }
}
