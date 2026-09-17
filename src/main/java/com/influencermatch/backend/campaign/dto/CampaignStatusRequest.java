package com.influencermatch.backend.campaign.dto;

import com.influencermatch.backend.campaign.CampaignStatus;
import jakarta.validation.constraints.NotNull;

public record CampaignStatusRequest(@NotNull CampaignStatus status) {}


