package com.influencermatch.backend.campaign.model;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.controller.*;
import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.campaign.service.*;
import com.influencermatch.backend.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "campaign")
@AttributeOverride(name = "id", column = @Column(updatable = false, nullable = false))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Campaign extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private BrandProfile brandProfile;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(nullable = false, length = 500)
  private String productService;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private CampaignObjective objective;

  @Builder.Default
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false)
  private List<String> targetAudiences = new ArrayList<>();

  @Builder.Default
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false)
  private List<String> platforms = new ArrayList<>();

  @Builder.Default
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false)
  private List<String> niches = new ArrayList<>();

  @Builder.Default
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false)
  private List<String> locations = new ArrayList<>();

  @Column private Long followerMin;

  @Column private Long followerMax;

  @Column(length = 100)
  private String contentType;

  @Column(precision = 19, scale = 2)
  private BigDecimal budgetMin;

  @Column(precision = 19, scale = 2)
  private BigDecimal budgetMax;

  @Column private LocalDate startDate;

  @Column private LocalDate endDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private CampaignStatus status;
}
