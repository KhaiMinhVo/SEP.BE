package com.influencermatch.backend.auth.model;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.auth.dto.*;
import com.influencermatch.backend.auth.controller.*;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_token")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, unique = true)
    private UUID jti;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column
    private LocalDateTime revokedAt;

    @Column
    private UUID replacedByTokenId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public boolean active(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
