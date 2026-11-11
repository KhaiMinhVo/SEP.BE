package com.influencermatch.backend.campaign.dto;

import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.campaign.enums.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Editable AI suggestion only; never a confirmed Campaign. */
public record CampaignAutofillResponse(
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
    String brief,
    CreatorMode creatorMode,
    Integer targetCreatorCount,
    List<CampaignDeliverable> deliverables,
    BigDecimal totalBudget,
    String currency,
    BigDecimal creatorFeeMin,
    BigDecimal creatorFeeMax,
    String aiExplanation) {}
