package com.influencermatch.backend.creator.model;

import com.influencermatch.backend.common.BaseEntity;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "creator_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CreatorMaster extends BaseEntity {

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", unique = true, nullable = false)
  private User user;

  @Column(name = "canonical_name", nullable = false)
  private String canonicalName;

  @Builder.Default
  @Column(nullable = false, length = 32)
  private String status = "ACTIVE"; // ACTIVE, SUSPENDED

  @Column(columnDefinition = "TEXT")
  private String notes;

  @Builder.Default
  @OneToMany(mappedBy = "master")
  private List<Creator> accounts = new ArrayList<>();
}
