package com.influencermatch.backend.auth.model;

import com.influencermatch.backend.auth.controller.*;
import com.influencermatch.backend.auth.dto.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.auth.service.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  private UUID sessionId;

  @Column(nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(nullable = false, unique = true)
  private UUID jti;

  @Column(nullable = false)
  private Instant expiresAt;

  @Column private Instant revokedAt;

  @Column private UUID replacedByTokenId;

  @Column(nullable = false)
  private Instant createdAt;

  public boolean active(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }
}
