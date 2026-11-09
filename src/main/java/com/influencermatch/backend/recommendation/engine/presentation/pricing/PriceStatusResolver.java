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
 * - Priority 4: ESTIMATED_OVER_BUDGET (Unconfirmed / estimated pricing exceeds budgetMax)
 * - Priority 5: CONFIRMED_OVER_BUDGET (Verified pricing strictly exceeds budgetMax)
 *
 * NOTE: candidatePrice vs budgetMax verifies individual creator affordability (Soft Preference),
 * not total campaign basket feasibility. Budget remains a soft preference.
 */
public class PriceStatusResolver {

  public static PriceStatus resolve(BigDecimal candidatePrice, Boolean isConfirmed, BigDecimal budgetMax) {
    if (candidatePrice == null || candidatePrice.compareTo(BigDecimal.ZERO) <= 0
        || budgetMax == null || budgetMax.compareTo(BigDecimal.ZERO) <= 0) {
      return PriceStatus.PRICE_UNKNOWN;
    }

    boolean withinBudget = candidatePrice.compareTo(budgetMax) <= 0;
    boolean confirmed = Boolean.TRUE.equals(isConfirmed);

    if (withinBudget) {
      return confirmed
          ? PriceStatus.CONFIRMED_WITHIN_BUDGET
          : PriceStatus.DECLARED_ESTIMATE;
    } else {
      return confirmed
          ? PriceStatus.CONFIRMED_OVER_BUDGET
          : PriceStatus.ESTIMATED_OVER_BUDGET;
    }
  }

  public static PriceStatus resolve(BigDecimal candidatePrice, Boolean isConfirmed, Campaign campaign) {
    BigDecimal budgetMax = (campaign != null) ? campaign.getBudgetMax() : null;
    return resolve(candidatePrice, isConfirmed, budgetMax);
  }
}
