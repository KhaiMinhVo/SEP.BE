package com.influencermatch.backend.billing.model;

import com.influencermatch.backend.billing.controller.*;
import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.brand.model.BrandProfile;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "subscription")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private BrandProfile brandProfile;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private Plan plan;

  @Column(nullable = false)
  private LocalDate startDate;

  @Column(nullable = false)
  private LocalDate expirationDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private SubscriptionStatus status;

  @Column(nullable = false)
  private boolean cancelAtPeriodEnd;
  @Column(nullable = false)
  private boolean refundHold;

  @Column(nullable = false)
  private int usedCampaignQuota;

  @Column(name = "used_ai_quota", nullable = false)
  private int usedRecommendationQuota;

  @Column(nullable = false)
  private int usedRefreshQuota;

  // Contract captured when the subscription is created; independent of catalog edits.
  @Column(nullable = false, length = 100)
  private String purchasedPlanName;
  @Column(nullable = false, precision = 19, scale = 2)
  private java.math.BigDecimal purchasedPrice;
  @Column(nullable = false, length = 3)
  private String purchasedCurrency;
  @Column(nullable = false)
  private Integer purchasedDurationDays;
  @Column(nullable = false)
  private Integer purchasedCampaignQuota;
  @Column(nullable = false)
  private Integer purchasedRecommendationQuota;
  @Column(nullable = false)
  private Integer purchasedRefreshQuota;
  private String purchasedDescription;

  @PrePersist
  public void capturePlan() {
    if (purchasedDurationDays != null || plan == null) return;
    purchasedPlanName = plan.getPlanName();
    purchasedPrice = plan.getPrice();
    purchasedCurrency = plan.getCurrency();
    purchasedDurationDays = plan.getDurationDays();
    purchasedCampaignQuota = plan.getCampaignQuota();
    purchasedRecommendationQuota = plan.getRecommendationQuota();
    purchasedRefreshQuota = plan.getRefreshQuota();
    purchasedDescription = plan.getDescription();
  }

  public String contractPlanName() {
    return purchasedPlanName != null ? purchasedPlanName : plan.getPlanName();
  }

  public int contractDurationDays() {
    return purchasedDurationDays != null ? purchasedDurationDays : plan.getDurationDays();
  }
}
