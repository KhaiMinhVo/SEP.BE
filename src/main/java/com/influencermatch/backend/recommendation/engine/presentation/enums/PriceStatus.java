package com.influencermatch.backend.recommendation.engine.presentation.enums;

/**
 * Budget and pricing readiness status for candidates.
 * Priority hierarchy for BUDGET_READY_FIRST display mode:
 * 1. CONFIRMED_WITHIN_BUDGET (Verified pricing <= budgetMax)
 * 2. DECLARED_ESTIMATE (Self-declared or historical estimate <= budgetMax)
 * 3. PRICE_UNKNOWN (No pricing data available or campaign budget unspecified)
 * 4. ESTIMATED_OVER_BUDGET (Estimated / unconfirmed pricing exceeds budgetMax)
 * 5. CONFIRMED_OVER_BUDGET (Contractually verified pricing strictly exceeds budgetMax)
 */
public enum PriceStatus {
  CONFIRMED_WITHIN_BUDGET(1),
  DECLARED_ESTIMATE(2),
  PRICE_UNKNOWN(3),
  ESTIMATED_OVER_BUDGET(4),
  CONFIRMED_OVER_BUDGET(5);

  private final int priority;

  PriceStatus(int priority) {
    this.priority = priority;
  }

  public int getPriority() {
    return priority;
  }
}

