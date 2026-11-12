package com.influencermatch.backend.auth.service;
import java.time.Instant;import java.util.UUID;
public interface OtpChallengeState {
 UUID getUserId();void setUserId(UUID id);String getCodeHash();void setCodeHash(String hash);
 Instant getExpiresAt();void setExpiresAt(Instant value);int getAttempts();void setAttempts(int value);
 Instant getConsumedAt();void setConsumedAt(Instant value);Instant getLastSentAt();void setLastSentAt(Instant value);
}
