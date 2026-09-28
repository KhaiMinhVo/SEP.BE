package com.influencermatch.backend.auth.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity @Table(name = "authExchangeCode")
public class AuthExchangeCode {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "userId", nullable = false)
    private UUID userId;
    @Column(name = "codeHash", nullable = false, length = 64)
    private String codeHash;
    @Column(name = "expiresAt", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "consumedAt")
    private LocalDateTime consumedAt;
    @Column(name = "createdAt", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
