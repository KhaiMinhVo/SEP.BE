package com.influencermatch.backend.billing.dto;

import com.influencermatch.backend.billing.controller.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.enums.PlanStatus;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
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

  /** plan.price is NUMERIC(19,2); VND has no minor unit, so only 17 whole digits are usable. */
  public static final int MAX_PRICE_DIGITS = 17;
  public static final int MAX_DURATION_DAYS = 3650;
  public static final int MAX_DESCRIPTION_LENGTH = 2000;

  private PlanDTOs() {}

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class PlanCreateRequest {
    @NotBlank(message = "Plan name is required")
    @Size(max = 100)
    @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*", message = "Plan name must not be blank")
    private String planName;

    @NotNull(message = "Price is required")
    @Min(value = 0, message = "Price cannot be negative")
    @DecimalMax(value = "9999999999", message = "Price exceeds the checkout amount limit")
    @Digits(integer = MAX_PRICE_DIGITS, fraction = 0, message = "VND price must be a whole number")
    private BigDecimal price;

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "VND", message = "Only VND plans are supported")
    private String currency;

    @Min(value = 1, message = "Duration must be at least 1 day")
    @Max(value = MAX_DURATION_DAYS, message = "Duration must not exceed 3650 days")
    private int durationDays;

    @Min(0)
    private int campaignQuota;

    @Min(0)
    private int recommendationQuota;

    @Min(0)
    private int refreshQuota;

    @Size(max = MAX_DESCRIPTION_LENGTH)
    private String description;
  }

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class PlanUpdateRequest {
    @Size(min = 1, max = 100)
    @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*", message = "Plan name must not be blank")
    private String planName;
    @Min(value = 0, message = "Price cannot be negative")
    @DecimalMax(value = "9999999999", message = "Price exceeds the checkout amount limit")
    @Digits(integer = MAX_PRICE_DIGITS, fraction = 0, message = "VND price must be a whole number")
    private BigDecimal price;
    @Pattern(regexp = "VND", message = "Only VND plans are supported")
    private String currency;
    @Min(value = 1, message = "Duration must be at least 1 day")
    @Max(value = MAX_DURATION_DAYS, message = "Duration must not exceed 3650 days")
    private Integer durationDays;
    @Min(0)
    private Integer campaignQuota;
    @Min(0)
    private Integer recommendationQuota;
    @Min(0)
    private Integer refreshQuota;
    @Size(max = MAX_DESCRIPTION_LENGTH)
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

    /**
     * Admin-only: ACTIVE, unexpired subscriptions on this plan. Catalog edits never change their
     * purchased terms; this only tells the admin how many Brands are currently on the plan.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long activeSubscriptionCount;
  }
}
