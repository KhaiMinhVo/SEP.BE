package com.influencermatch.backend.recommendation.engine.presentation.pricing;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.recommendation.engine.presentation.enums.PriceStatus;
import java.math.BigDecimal;

/**
 * Phase 6 Pricing Readiness Resolver.
 * Maps candidate pricing data against campaign budget limits:
 * - Priority 1: CONFIRMED_WITHIN_BUDGET (Verified pricing <= budgetMax)
 * - Priority 2: DECLARED_ESTIMATE (Self-declared or estimated pricing <= budgetMax)
 * - Priority 3: PRICE_UNKNOWN (Missing rate card or unspecified budget)
 * - Priority 4: CONFIRMED_OVER_BUDGET (Pricing strictly exceeds budgetMax)
 */
public class PriceStatusResolver {

  public static PriceStatus resolve(BigDecimal candidatePrice, Boolean isConfirmed, BigDecimal budgetMax) {
    if (candidatePrice == null || candidatePrice.compareTo(BigDecimal.ZERO) <= 0
        || budgetMax == null || budgetMax.compareTo(BigDecimal.ZERO) <= 0) {
      return PriceStatus.PRICE_UNKNOWN;
    }

    boolean withinBudget = candidatePrice.compareTo(budgetMax) <= 0;

    if (withinBudget) {
      return Boolean.TRUE.equals(isConfirmed)
          ? PriceStatus.CONFIRMED_WITHIN_BUDGET
          : PriceStatus.DECLARED_ESTIMATE;
    } else {
      return PriceStatus.CONFIRMED_OVER_BUDGET;
    }
  }

  public static PriceStatus resolve(BigDecimal candidatePrice, Boolean isConfirmed, Campaign campaign) {
    BigDecimal budgetMax = (campaign != null) ? campaign.getBudgetMax() : null;
    return resolve(candidatePrice, isConfirmed, budgetMax);
  }
}
