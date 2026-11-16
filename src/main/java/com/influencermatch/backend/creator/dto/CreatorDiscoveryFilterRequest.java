package com.influencermatch.backend.creator.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorDiscoveryFilterRequest {
  private String keyword;
  private Long minFollowers;
  private Long maxFollowers;
  private String platform;
  private String niche;
  private String tier;          // NANO (<10k), MICRO (10k-100k), MACRO (100k-1M), MEGA (>1M)
  private BigDecimal minEr;
  private BigDecimal maxEr;
  private Boolean isClaimed;
  private BigDecimal minFee;
  private BigDecimal maxFee;
  private String sortBy;        // DATABASE_RELEVANCE (legacy SEMANTIC_RELEVANCE alias), BUDGET_FIT, LOWEST_COMPARABLE_FEE
}

