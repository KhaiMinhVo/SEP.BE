package com.influencermatch.backend.auth.model;

import com.influencermatch.backend.auth.service.OtpChallengeState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "password_reset_otp")
@Getter
@Setter
public class PasswordResetOtp implements OtpChallengeState {
  @Id private UUID userId;
  @Column(nullable = false, length = 64) private String codeHash;
  @Column(nullable = false) private Instant expiresAt;
  @Column(nullable = false) private Instant lastSentAt;
  @Column(nullable = false) private int attempts;
  private Instant consumedAt;
  @Column(nullable = false) private long authVersion;
}
