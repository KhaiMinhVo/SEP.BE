package com.influencermatch.backend.billing.dto;

import com.influencermatch.backend.billing.controller.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.enums.PlanStatus;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

public final class PlanDTOs {

  private PlanDTOs() {}

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class PlanCreateRequest {
    @NotBlank(message = "Plan name is required")
    @Size(max = 100)
    private String planName;

    @NotNull(message = "Price is required")
    @Min(value = 0, message = "Price cannot be negative")
    private BigDecimal price;

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "VND", message = "Only VND plans are supported")
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

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class PlanUpdateRequest {
    @Size(min = 1, max = 100)
    @Pattern(regexp = ".*\\S.*", message = "Plan name must not be blank")
    private String planName;
    @Min(value = 0, message = "Price cannot be negative")
    private BigDecimal price;
    @Pattern(regexp = "VND", message = "Only VND plans are supported")
    private String currency;
    @Min(value = 1, message = "Duration must be at least 1 day")
    private Integer durationDays;
    @Min(0)
    private Integer campaignQuota;
    @Min(0)
    private Integer recommendationQuota;
    @Min(0)
    private Integer refreshQuota;
    private String description;
    private PlanStatus status;
  }

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
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
