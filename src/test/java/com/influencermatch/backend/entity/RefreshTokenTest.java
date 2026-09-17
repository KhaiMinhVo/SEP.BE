package com.influencermatch.backend.entity;
import com.influencermatch.backend.auth.RefreshToken;
import org.junit.jupiter.api.Test; import java.time.LocalDateTime; import static org.assertj.core.api.Assertions.assertThat;
class RefreshTokenTest {
 @Test void activeOnlyWhenNotExpiredOrRevoked(){LocalDateTime now=LocalDateTime.now();RefreshToken token=RefreshToken.builder().expiresAt(now.plusMinutes(1)).build();assertThat(token.active(now)).isTrue();token.setRevokedAt(now);assertThat(token.active(now)).isFalse();token.setRevokedAt(null);token.setExpiresAt(now.minusSeconds(1));assertThat(token.active(now)).isFalse();}
}


