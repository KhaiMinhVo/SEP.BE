package com.influencermatch.backend.campaign.dto;

import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.campaign.enums.*;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Suggested minimum budget in VND", example = "10000000")
    BigDecimal budgetMin,

    @Schema(description = "Suggested maximum budget in VND", example = "30000000")
    BigDecimal budgetMax,

    @ArraySchema(schema = @Schema(description = "Fields explicitly extracted from the user's prompt", example = "[\"productService\", \"platforms\", \"budgetMin\"]"))
    List<String> extractedFields,

    @ArraySchema(schema = @Schema(description = "Fields inferred/suggested by AI due to missing information in the prompt", example = "[\"followerMin\", \"followerMax\", \"targetAudiences\"]"))
    List<String> inferredFields,

    String brief,
    CreatorMode creatorMode,
    Integer targetCreatorCount,
    List<CampaignDeliverable> deliverables,
    BigDecimal totalBudget,
    String currency,
    BigDecimal creatorFeeMin,
    BigDecimal creatorFeeMax,

    @Schema(description = "AI explanation of why these parameters were chosen", example = "Chiến dịch tập trung vào phân khúc Gen Z yêu thích làm đẹp tự nhiên, phù hợp với các KOC trên TikTok và Instagram.")
    String aiExplanation) {}
