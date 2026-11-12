package com.influencermatch.backend.auth.model;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
@Entity @Table(name="password_setup_otp") @Getter @Setter @NoArgsConstructor
public class PasswordSetupOtp implements com.influencermatch.backend.auth.service.OtpChallengeState {
  @Id private UUID userId;
  @Column(nullable=false,length=64) private String codeHash;
  @Column(nullable=false) private Instant expiresAt;
  @Column(nullable=false) private Instant lastSentAt;
  @Column(nullable=false) private int attempts;
  private Instant consumedAt;
}
