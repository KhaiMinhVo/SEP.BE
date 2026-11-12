package com.influencermatch.backend.billing.model;

import com.influencermatch.backend.billing.controller.*;
import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(
    name = "plan",
    uniqueConstraints = @UniqueConstraint(name = "ux_plan_name", columnNames = "plan_name"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Plan {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @Column(nullable = false, length = 100)
  private String planName;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal price;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false)
  private int durationDays;

  @Column(nullable = false)
  private int campaignQuota;

  @Column(name = "ai_recommendation_quota", nullable = false)
  private int recommendationQuota;

  @Column(nullable = false)
  private int refreshQuota;

  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PlanStatus status;

  @Column(nullable = false)
  private LocalDateTime createdAt;
}
