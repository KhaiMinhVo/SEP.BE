package com.influencermatch.backend.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CampaignAutofillRequest(
    @NotBlank(message = "Prompt must not be blank")
    @Size(min = 10, max = 1000, message = "Prompt must be between 10 and 1000 characters")
    String prompt) {}
