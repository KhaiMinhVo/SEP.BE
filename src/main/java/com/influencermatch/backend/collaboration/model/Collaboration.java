package com.influencermatch.backend.collaboration.model;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.collaboration.enums.CollaborationPaymentStatus;
import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(
    name = "collaboration",
    uniqueConstraints =
        @UniqueConstraint(name = "ux_collaboration_relationship", columnNames = "relationship_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Collaboration {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private BrandCreatorRelationship relationship;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private Campaign campaign;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private Creator creator;

  @Column(precision = 19, scale = 2)
  private BigDecimal agreedFee;

  @Column(length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private CollaborationStatus status;

  @Column(length = 1000)
  private String publishedContentUrl;

  private LocalDateTime startAt;
  private LocalDateTime completedAt;
  private String note;

  @Enumerated(EnumType.STRING)
  @Column(length = 32)
  private CollaborationPaymentStatus paymentStatus;

  @Column(nullable = false)
  private LocalDateTime updatedAt;
}
