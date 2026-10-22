package com.influencermatch.backend.creator.model;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.common.BaseEntity;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity(name = "CreatorClaimInvitation")
@Table(
    name = "creator_claim_invitation",
    indexes = {
      @Index(name = "idx_claim_invitation_token", columnList = "token_hash"),
      @Index(name = "idx_claim_invitation_brand", columnList = "brand_id, status")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CreatorInvitation extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "target_creator_id", nullable = false)
  private Creator targetCreator;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "campaign_id")
  private Campaign campaign;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "brand_id")
  private BrandProfile brand;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "invited_by", nullable = false)
  private User invitedBy;

  @Column(name = "invited_email", nullable = false, length = 255)
  private String invitedEmail;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Builder.Default
  @Column(nullable = false, length = 32)
  private String status = "PENDING"; // PENDING, ACCEPTED, EXPIRED, CANCELLED

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;
}
