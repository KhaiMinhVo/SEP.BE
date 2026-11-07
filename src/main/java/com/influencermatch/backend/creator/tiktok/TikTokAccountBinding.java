package com.influencermatch.backend.creator.tiktok;

import com.influencermatch.backend.common.BaseEntity;
import com.influencermatch.backend.creator.model.Creator;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "tiktok_account_binding")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class TikTokAccountBinding extends BaseEntity {
  @Column(nullable = false, length = 16)
  private String environment;

  @Column(nullable = false, length = 255)
  private String clientKey;

  @Column(nullable = false, length = 255)
  private String openId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "creator_id", nullable = false)
  private Creator creator;

  @Column(nullable = false, length = 255)
  private String username;

  @Column(nullable = false)
  private Instant verifiedAt;
}
