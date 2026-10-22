package com.influencermatch.backend.creator.model;

import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
    name = "creator",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"platform", "external_id"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Creator {
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "master_id")
  @com.fasterxml.jackson.annotation.JsonIgnore
  private CreatorMaster master;

  @Builder.Default
  @Column(name = "is_stable_id", nullable = false)
  private Boolean isStableId = true;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 16)
  private com.influencermatch.backend.creator.enums.CreatorSourceType sourceType = com.influencermatch.backend.creator.enums.CreatorSourceType.PUBLIC;

  private LocalDateTime authorizedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_user_id")
  @com.fasterxml.jackson.annotation.JsonIgnore
  private User owner;

  public User getOwner() {
    return owner != null ? owner : (master != null ? master.getUser() : null);
  }

  public void setMaster(CreatorMaster master) {
    this.master = master;
    this.owner = master != null ? master.getUser() : null;
  }

  public void setOwner(User owner) {
    this.owner = owner;
    if (owner == null) this.master = null;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @Column(nullable = false, length = 50)
  private String platform;

  @Column(name = "external_id", nullable = false, length = 255)
  private String externalId;

  @Column(name = "user_name", nullable = false, length = 255)
  private String userName;

  @Column(length = 150)
  private String location;

  @Column(name = "profile_url", length = 1000)
  private String profileUrl;

  @Column(name = "display_name", length = 255)
  private String displayName;

  @Column(name = "avatar_url", length = 1000)
  private String avatarUrl;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}
