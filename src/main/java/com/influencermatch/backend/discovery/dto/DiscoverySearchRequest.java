package com.influencermatch.backend.discovery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object representing the search parameters submitted by the Brand
 * from the Discovery UI (e.g., keyword search, page navigation, extra filters).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DiscoverySearchRequest {
    private String keyword;
    
    @Builder.Default
    private Integer page = 0;
    
    @Builder.Default
    private Integer size = 20;
    
    private Long minFollowers;
    private Long maxFollowers;
    private String niche;
    private String platform;
    private String tier;
    private java.math.BigDecimal minEr;
    private java.math.BigDecimal maxEr;
    private Boolean isClaimed;
    private java.math.BigDecimal minFee;
    private java.math.BigDecimal maxFee;
    private String sortBy;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    @Builder.Default
    private java.math.BigDecimal minimumDataConfidence = java.math.BigDecimal.valueOf(
        com.influencermatch.backend.discovery.service.CreatorDiscoveryContract.DEFINITION.minDataConfidence());

    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    @Builder.Default
    private boolean taxonomyKeyword = false;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    @Builder.Default
    private java.util.List<String> keywordNiches = java.util.List.of("__NONE__");

    // Server-built campaign context for deterministic retrieval scoring, before AI review.
    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    private String relevanceQuery;

    // Internal retrieval mode. The service always resets client-provided values.
    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    @Builder.Default
    private boolean similarityExpansion = false;

}
