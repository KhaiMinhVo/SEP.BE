package com.influencermatch.backend.brand.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "brand_pattern")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandPattern {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "brand_profile_id", nullable = false)
  private BrandProfile brandProfile;

  @Column(length = 50)
  private String platform;

  @Column(length = 120)
  private String niche;

  @Column(name = "average_successful_score", precision = 7, scale = 4)
  private BigDecimal averageSuccessfulScore;

  @Column(name = "average_agreed_fee", precision = 19, scale = 2)
  private BigDecimal averageAgreedFee;

  @Column(name = "preferred_engagement_rate", precision = 7, scale = 4)
  private BigDecimal preferredEngagementRate;

  @Column(name = "pattern_type", nullable = false, length = 100)
  private String patternType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "pattern_value", nullable = false)
  private JsonNode patternValue;

  @Column(nullable = false, precision = 5, scale = 4)
  private BigDecimal confidence;

  @Column(name = "evidence_count", nullable = false)
  private int evidenceCount;

  @Column(name = "learned_at", nullable = false)
  private LocalDateTime learnedAt;
}
