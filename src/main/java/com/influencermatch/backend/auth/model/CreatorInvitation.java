package com.influencermatch.backend.auth.model;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
@Entity @Table(name="creator_invitation") @Getter @Setter @NoArgsConstructor
public class CreatorInvitation {
  @Id private UUID id;
  @Column(nullable=false,unique=true,length=255) private String email;
  @Column(nullable=false,unique=true,length=64) private String tokenHash;
  @Column(nullable=false) private Instant expiresAt;
  @Column(nullable=false) private Instant lastSentAt;
  private Instant consumedAt;
  @Column(nullable=false) private UUID invitedBy;
}
