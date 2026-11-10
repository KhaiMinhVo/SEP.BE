package com.influencermatch.backend.campaign.dto;

import com.influencermatch.backend.brand.enums.Platform;
import jakarta.validation.constraints.*;

public record CampaignDeliverable(
    @NotNull Platform platform,
    @NotBlank @Size(max = 100) String contentType,
    @NotNull @Positive Integer quantity) {}
