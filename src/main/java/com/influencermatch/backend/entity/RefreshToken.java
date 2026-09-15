package com.influencermatch.backend.entity;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="refresh_tokens") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="user_id",nullable=false) private UUID userId;
    @Column(name="token_hash",nullable=false,unique=true,length=64) private String tokenHash;
    @Column(nullable=false,unique=true) private UUID jti;
    @Column(name="expires_at",nullable=false) private LocalDateTime expiresAt;
    @Column(name="revoked_at") private LocalDateTime revokedAt;
    @Column(name="replaced_by_token_id") private UUID replacedByTokenId;
    @Column(name="created_at",nullable=false) private LocalDateTime createdAt;
    public boolean active(LocalDateTime now){return revokedAt==null&&expiresAt.isAfter(now);}
}
