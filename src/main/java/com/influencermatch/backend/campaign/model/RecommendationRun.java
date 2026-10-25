package com.influencermatch.backend.campaign.model;

import com.influencermatch.backend.brand.model.BrandProfile;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "recommendation_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationRun {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "campaign_id", nullable = false)
  private Campaign campaign;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "brand_profile_id", nullable = false)
  private BrandProfile brandProfile;

  @Builder.Default
  @Column(name = "context_version", nullable = false)
  private int contextVersion = 1;

  @Builder.Default
  @Column(nullable = false, length = 32)
  private String status = "COMPLETED";

  @Builder.Default
  @Column(name = "candidates_count", nullable = false)
  private int candidatesCount = 0;

  @Column(name = "query_brief", columnDefinition = "TEXT")
  private String queryBrief;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
