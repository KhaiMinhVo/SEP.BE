package com.influencermatch.backend.collaboration.model;

import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(
    name = "creator_review",
    uniqueConstraints = {
      @UniqueConstraint(name = "ux_creator_review_outcome", columnNames = "outcome_id"),
      @UniqueConstraint(name = "ux_creator_review_collaboration", columnNames = "collaboration_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatorReview {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private CampaignOutcome outcome;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private Collaboration collaboration;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reviewed_by_user_id", nullable = false)
  private User reviewedBy;

  @Column(nullable = false)
  private short overallRating;

  @Column(nullable = false)
  private short brandFitRating;

  private Boolean wouldCollaborateAgain;
  private String note;

  @Column(nullable = false)
  private LocalDateTime reviewedAt;
}
