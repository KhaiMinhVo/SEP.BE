package com.influencermatch.backend.relationship.model;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.RecommendationRun;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistSource;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(
    name = "shortlist_item",
    uniqueConstraints =
        @UniqueConstraint(
            name = "ux_shortlist_campaign_creator",
            columnNames = {"campaign_id", "creator_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortlistItem {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private Campaign campaign;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private Creator creator;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "master_id")
  private com.influencermatch.backend.creator.model.CreatorMaster master;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ShortlistPriority priority;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ShortlistStatus status = ShortlistStatus.SAVED;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ShortlistSource source = ShortlistSource.MANUAL_DISCOVERY;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "recommendation_run_id")
  private RecommendationRun recommendationRun;

  private String note;

  @Column(nullable = false)
  private LocalDateTime addedAt;
}
