package com.influencermatch.backend.campaign.dto;

import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.campaign.enums.CampaignObjective;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * AI-generated suggestion to pre-fill the campaign creation form.
 * All fields are nullable – the frontend should show them as editable suggestions.
 */
public record CampaignAutofillResponse(
    @Schema(description = "Concise campaign name", example = "Chiến Dịch Ra Mắt Son Dưỡng Hè 2026")
    String name,

    @Schema(description = "Product or service to be promoted", example = "Son dưỡng thuần chay chiết xuất bơ hạt mỡ")
    String productService,

    @Schema(description = "Campaign marketing objective", example = "AWARENESS")
    CampaignObjective objective,

    @ArraySchema(schema = @Schema(description = "Target audience segment", example = "Gen Z (18-24 tuổi) quan tâm mỹ phẩm thuần chay"))
    List<String> targetAudiences,

    @Schema(description = "Content format", example = "Video ngắn review trải nghiệm và swatch son thực tế")
    String contentType,

    @Schema(description = "Suggested start date", example = "2026-11-01")
    LocalDate startDate,

    @Schema(description = "Suggested end date", example = "2026-12-01")
    LocalDate endDate,

    @ArraySchema(schema = @Schema(description = "Target social media platforms (TIKTOK, YOUTUBE, INSTAGRAM)", example = "TIKTOK"))
    @Schema(description = "List of target platforms", example = "[\"TIKTOK\", \"YOUTUBE\", \"INSTAGRAM\"]")
    List<Platform> platforms,

    @ArraySchema(schema = @Schema(description = "Niches/categories", example = "BEAUTY"))
    List<String> niches,

    @ArraySchema(schema = @Schema(description = "Target geographic locations", example = "Việt Nam"))
    List<String> locations,

    @Schema(description = "Suggested minimum follower count", example = "10000")
    Long followerMin,

    @Schema(description = "Suggested maximum follower count", example = "100000")
    Long followerMax,

    @Schema(description = "Suggested minimum budget in VND", example = "10000000")
    BigDecimal budgetMin,

    @Schema(description = "Suggested maximum budget in VND", example = "30000000")
    BigDecimal budgetMax,

    @Schema(description = "AI explanation of why these parameters were chosen", example = "Chiến dịch tập trung vào phân khúc Gen Z yêu thích làm đẹp tự nhiên, phù hợp với các KOC trên TikTok và Instagram.")
    String aiExplanation) {}
