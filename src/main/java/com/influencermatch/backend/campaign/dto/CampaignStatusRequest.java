package com.influencermatch.backend.campaign.dto;

import com.influencermatch.backend.campaign.controller.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.campaign.service.*;
import jakarta.validation.constraints.NotNull;

public record CampaignStatusRequest(@NotNull CampaignStatus status,
    @jakarta.validation.constraints.Size(max=2000) String reason) {
  public CampaignStatusRequest(CampaignStatus status) { this(status, null); }
}
