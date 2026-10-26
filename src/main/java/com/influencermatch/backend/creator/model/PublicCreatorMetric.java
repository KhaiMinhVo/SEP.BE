package com.influencermatch.backend.creator.model;

import com.influencermatch.backend.creator.enums.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "public_creator_metric")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicCreatorMetric {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "quality_reviewed_by")
  @com.fasterxml.jackson.annotation.JsonIgnore
  private com.influencermatch.backend.user.model.User qualityReviewedBy;

  private LocalDateTime qualityReviewedAt;

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

  @Column(name = "content_summary", columnDefinition = "TEXT")
  private String contentSummary;

  @Column(name = "collected_at", nullable = false)
  private LocalDateTime collectedAt;

  @Column(name = "crawl_run_id", length = 64)
  private String crawlRunId;

  @Column(name = "observed_at")
  private LocalDateTime observedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "freshness_status", nullable = false, length = 32)
  private MetricDataStatus freshnessStatus;

  @Column(name = "data_confidence", precision = 5, scale = 4)
  private BigDecimal dataConfidence;

  @Column(name = "creator_type", length = 50)
  private String creatorType;

  @Column(length = 500)
  private String contact;

  @Column(length = 120)
  private String category;
  
  // Maps the TSVECTOR column to JPA for use in Criteria API search queries
  @Column(name = "search_vector", columnDefinition = "tsvector", insertable = false, updatable = false)
  @JdbcTypeCode(SqlTypes.OTHER)
  private Object searchVector;
}
