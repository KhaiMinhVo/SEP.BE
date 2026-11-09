package com.influencermatch.backend.campaign.dto;

import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.campaign.controller.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.campaign.service.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public record CampaignRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 500) String productService,
    CampaignObjective objective,
    @Size(max = 20) List<@NotBlank @Size(max = 150) String> targetAudiences,
    @Size(max = 100) String contentType,
    LocalDate startDate,
    LocalDate endDate,
    @Size(max = 3) List<@NotNull Platform> platforms,
    @Size(max = 20) List<@NotBlank @Size(max = 100) String> niches,
    @Size(max = 20) List<@NotBlank @Size(max = 150) String> locations,
    @PositiveOrZero Long followerMin,
    @PositiveOrZero Long followerMax,
    @io.swagger.v3.oas.annotations.media.Schema(
            deprecated = true,
            description = "Legacy read-only budget; use totalBudget/creatorFeeMin")
        BigDecimal budgetMin,
    @io.swagger.v3.oas.annotations.media.Schema(
            deprecated = true,
            description = "Legacy read-only budget; use totalBudget/creatorFeeMax")
        BigDecimal budgetMax,
    @Size(max = 10000) String brief,
    CreatorMode creatorMode,
    @Positive Integer targetCreatorCount,
    @Size(max = 100) List<@jakarta.validation.Valid @NotNull CampaignDeliverable> deliverables,
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2)
        BigDecimal totalBudget,
    @Pattern(regexp = "VND") String currency,
    @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal creatorFeeMin,
    @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal creatorFeeMax) {
  public CampaignRequest(
      String name,
      String productService,
      CampaignObjective objective,
      List<String> targetAudiences,
      String contentType,
      LocalDate startDate,
      LocalDate endDate,
      List<Platform> platforms,
      List<String> niches,
      List<String> locations,
      Long followerMin,
      Long followerMax,
      BigDecimal budgetMin,
      BigDecimal budgetMax) {
    this(
        name,
        productService,
        objective,
        targetAudiences,
        contentType,
        startDate,
        endDate,
        platforms,
        niches,
        locations,
        followerMin,
        followerMax,
        budgetMin,
        budgetMax,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
