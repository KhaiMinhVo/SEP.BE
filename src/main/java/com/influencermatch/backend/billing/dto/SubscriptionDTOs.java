package com.influencermatch.backend.billing.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.time.LocalDate;
import java.util.UUID;
import com.influencermatch.backend.billing.SubscriptionStatus;

public final class SubscriptionDTOs {

    private SubscriptionDTOs() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SubscriptionCreateRequest {
        @NotNull(message = "Brand ID is required")
        private UUID brandId;

        @NotNull(message = "Plan ID is required")
        private UUID planId;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
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
