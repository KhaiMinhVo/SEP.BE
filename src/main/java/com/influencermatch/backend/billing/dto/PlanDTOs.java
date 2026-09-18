package com.influencermatch.backend.billing.dto;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.controller.*;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.influencermatch.backend.billing.enums.PlanStatus;

public final class PlanDTOs {

    private PlanDTOs() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PlanCreateRequest {
        @NotBlank(message = "Plan name is required")
        private String planName;

        @NotNull(message = "Price is required")
        @Min(value = 0, message = "Price cannot be negative")
        private BigDecimal price;

        @NotBlank(message = "Currency is required")
        private String currency;

        @Min(value = 1, message = "Duration must be at least 1 day")
        private int durationDays;

        @Min(0)
        private int campaignQuota;

        @Min(0)
        private int recommendationQuota;

        @Min(0)
        private int refreshQuota;

        private String description;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PlanUpdateRequest {
        private String planName;
        private BigDecimal price;
        private String currency;
        private Integer durationDays;
        private Integer campaignQuota;
        private Integer recommendationQuota;
        private Integer refreshQuota;
        private String description;
        private PlanStatus status;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PlanResponse {
        private UUID id;
        private String planName;
        private BigDecimal price;
        private String currency;
        private int durationDays;
        private int campaignQuota;
        private int recommendationQuota;
        private int refreshQuota;
        private String description;
        private PlanStatus status;
        private LocalDateTime createdAt;
    }
}
