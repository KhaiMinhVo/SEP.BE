package com.influencermatch.backend.creator.model;

import com.influencermatch.backend.common.BaseEntity;
import jakarta.persistence.*;
import java.util.Map;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "creator_provider_identity",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_provider_identity",
          columnNames = {"provider", "provider_user_id"})
    },
    indexes = {
      @Index(name = "idx_provider_identity_creator", columnList = "creator_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CreatorProviderIdentity extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "creator_id", nullable = false)
  private Creator creator;

  @Column(nullable = false, length = 50)
  private String provider; // TIKTOK, YOUTUBE, FACEBOOK, INSTAGRAM

  @Column(name = "provider_user_id", nullable = false, length = 255)
  private String providerUserId;

  @Column(name = "provider_username", length = 255)
  private String providerUsername;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "raw_profile_data")
  private Map<String, Object> rawProfileData;
}
