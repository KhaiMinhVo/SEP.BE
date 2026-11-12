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
}
