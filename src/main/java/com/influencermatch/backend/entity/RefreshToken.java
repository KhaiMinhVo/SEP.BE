package com.influencermatch.backend.entity;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="refreshToken") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="userId",nullable=false) private UUID userId;
    @Column(name="tokenHash",nullable=false,unique=true,length=64) private String tokenHash;
    @Column(nullable=false,unique=true) private UUID jti;
    @Column(name="expiresAt",nullable=false) private LocalDateTime expiresAt;
    @Column(name="revokedAt") private LocalDateTime revokedAt;
    @Column(name="replacedByTokenId") private UUID replacedByTokenId;
    @Column(name="createdAt",nullable=false) private LocalDateTime createdAt;
    public boolean active(LocalDateTime now){return revokedAt==null&&expiresAt.isAfter(now);}
}
