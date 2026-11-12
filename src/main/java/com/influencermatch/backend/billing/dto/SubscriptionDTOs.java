package com.influencermatch.backend.billing.dto;

import com.influencermatch.backend.billing.controller.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.enums.SubscriptionStatus;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;

public final class SubscriptionDTOs {

  private SubscriptionDTOs() {}

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class SubscriptionCreateRequest {
    @NotNull(message = "Brand ID is required")
    private UUID brandId;

    @NotNull(message = "Plan ID is required")
    private UUID planId;
  }

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class SubscriptionResponse {
    private UUID id;
    private UUID brandId;
    private PlanDTOs.PlanResponse plan;
    private LocalDate startDate;
    private LocalDate expirationDate;
    private SubscriptionStatus status;
    private int usedCampaignQuota;
    private int usedRecommendationQuota;
    private int usedRefreshQuota;
  }
}
