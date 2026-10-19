package com.influencermatch.backend.campaign.dto;

import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.campaign.enums.CampaignObjective;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * AI-generated suggestion to pre-fill the campaign creation form.
 * All fields are nullable – the frontend should show them as editable suggestions.
 */
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
    BigDecimal budgetMin,
    BigDecimal budgetMax,
    /** Human-readable explanation of why AI made these choices */
    String aiExplanation) {}
