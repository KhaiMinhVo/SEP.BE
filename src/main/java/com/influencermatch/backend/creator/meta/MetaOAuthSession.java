package com.influencermatch.backend.creator.meta;

import com.influencermatch.backend.common.BaseEntity;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "meta_oauth_session")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class MetaOAuthSession extends BaseEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  private long authVersion;

  @Column(nullable = false, length = 20)
  private String platform;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private OAuthFlow oauthFlow = OAuthFlow.FACEBOOK_LOGIN;

  @Column(nullable = false, unique = true, length = 64)
  private String stateHash;

  @Column(nullable = false, length = 64)
  private String browserBindingHash;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(nullable = false)
  private Instant expiresAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "target_creator_id")
  private Creator targetCreator;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "invitation_id")
  private CreatorInvitation invitation;

  @Column(columnDefinition = "text")
  private String payloadCiphertext;

  @Column(length = 255)
  private String confirmedAccountId;
}
