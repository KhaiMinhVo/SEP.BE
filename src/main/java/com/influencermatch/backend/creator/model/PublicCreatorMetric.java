package com.influencermatch.backend.creator.model;

import com.influencermatch.backend.creator.controller.*;
import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "public_creator_metric")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicCreatorMetric {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "creator_id", nullable = false)
  private Creator creator;

  private Long followers;

  @Column(name = "avg_views", precision = 19, scale = 2)
  private BigDecimal avgViews;

  @Column(name = "avg_likes", precision = 19, scale = 2)
  private BigDecimal avgLikes;

  @Column(name = "avg_comments", precision = 19, scale = 2)
  private BigDecimal avgComments;

  @Column(name = "avg_shares", precision = 19, scale = 2)
  private BigDecimal avgShares;

  @Column(name = "engagement_rate", precision = 7, scale = 4)
  private BigDecimal engagementRate;

  @Column(length = 120)
  private String niche;

  @Column(length = 150)
  private String location;

  @Column(name = "content_summary", columnDefinition = "TEXT")
  private String contentSummary;

  @Column(name = "collected_at", nullable = false)
  private LocalDateTime collectedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "freshness_status", nullable = false, length = 32)
  private MetricDataStatus freshnessStatus;

  @Column(name = "data_confidence", precision = 5, scale = 4)
  private BigDecimal dataConfidence;

  @Column(length = 500)
  private String contact;

  @Column(name = "url_profile", length = 1000)
  private String urlProfile;

  @Column(length = 120)
  private String category;
}
