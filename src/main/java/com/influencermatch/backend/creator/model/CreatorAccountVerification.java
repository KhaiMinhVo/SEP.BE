package com.influencermatch.backend.creator.model;

import com.influencermatch.backend.common.BaseEntity;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(
    name = "creator_account_verification",
    indexes = {
      @Index(name = "idx_account_verification_creator", columnList = "creator_id, status")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CreatorAccountVerification extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "creator_id", nullable = false)
  private Creator creator;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "verified_by_user_id", nullable = false)
  private User verifiedByUser;

  @Builder.Default
  @Column(name = "verification_method", nullable = false, length = 50)
  private String verificationMethod = "OAUTH_AUTHORIZATION";

  @Column(name = "granted_scopes", columnDefinition = "TEXT")
  private String grantedScopes;

  @Column(name = "access_token_encrypted", columnDefinition = "TEXT")
  private String accessTokenEncrypted;

  @Column(name = "refresh_token_encrypted", columnDefinition = "TEXT")
  private String refreshTokenEncrypted;

  @Column(name = "token_expires_at")
  private LocalDateTime tokenExpiresAt;

  @Builder.Default
  @Column(nullable = false, length = 32)
  private String status = "ACTIVE"; // ACTIVE, REVOKED, EXPIRED

  @Builder.Default
  @Column(name = "verified_at", nullable = false)
  private LocalDateTime verifiedAt = LocalDateTime.now();
}
