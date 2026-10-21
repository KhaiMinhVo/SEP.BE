package com.influencermatch.backend.discovery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Data Transfer Object representing the "Current Campaign Context" (M3).
 * It holds the hard constraints extracted from a Campaign when it was activated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscoveryContext {
    private List<String> platforms;
    private List<String> niches;
    private Long followerMin;
    private Long followerMax;
    private BigDecimal budgetMin;
    private BigDecimal budgetMax;
}
