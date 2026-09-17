package com.influencermatch.backend.campaign.dto;

import com.influencermatch.backend.brand.Platform;
import com.influencermatch.backend.campaign.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public record CampaignRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 500) String productService,
        @NotNull CampaignObjective objective,
        @Size(max = 20) List<@NotBlank @Size(max = 150) String> targetAudiences,
        @Size(max = 100) String contentType,
        LocalDate startDate, LocalDate endDate,
        @NotEmpty @Size(max = 3) List<Platform> platforms,
        @Size(max = 20) List<@NotBlank @Size(max = 100) String> niches,
        @Size(max = 20) List<@NotBlank @Size(max = 150) String> locations,
        @PositiveOrZero Long followerMin, @PositiveOrZero Long followerMax,
        @PositiveOrZero BigDecimal budgetMin, @PositiveOrZero BigDecimal budgetMax
) {}


