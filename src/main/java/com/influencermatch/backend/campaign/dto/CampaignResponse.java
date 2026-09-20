package com.influencermatch.backend.campaign.dto;

import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.campaign.controller.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.campaign.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public record CampaignResponse(
    UUID id,
    UUID brandId,
    String name,
    String productService,
    CampaignObjective objective,
    List<String> targetAudiences,
    String contentType,
    LocalDate startDate,
    LocalDate endDate,
    CampaignStatus status,
    List<Platform> platforms,
    List<String> niches,
    List<String> locations,
    Long followerMin,
    Long followerMax,
    BigDecimal budgetMin,
    BigDecimal budgetMax,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
