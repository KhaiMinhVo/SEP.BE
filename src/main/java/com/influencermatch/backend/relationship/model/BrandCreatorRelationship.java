package com.influencermatch.backend.relationship.model;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(
    name = "relationship",
    uniqueConstraints =
        @UniqueConstraint(
            name = "ux_relationship_campaign_creator",
            columnNames = {"campaign_id", "creator_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandCreatorRelationship {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private Creator creator;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private Campaign campaign;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn
  private ShortlistItem shortlistItem;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private RelationshipStage stage;

  @Column(length = 100)
  private String contactMethod;

  @Column(precision = 19, scale = 2)
  private BigDecimal quotedFee;

  @Column(precision = 19, scale = 2)
  private BigDecimal agreedFee;

  @Column(length = 3)
  private String currency;

  private LocalDateTime lastContactAt;
  private String notes;

  @Column(length = 500)
  private String nextAction;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column(nullable = false)
  private LocalDateTime updatedAt;
}
