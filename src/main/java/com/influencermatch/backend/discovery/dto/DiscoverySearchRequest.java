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
@Builder
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

}
