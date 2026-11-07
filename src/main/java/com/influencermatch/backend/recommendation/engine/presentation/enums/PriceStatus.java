package com.influencermatch.backend.recommendation.engine.presentation.enums;

/**
 * Budget and pricing readiness status for candidates.
 */
public enum PriceStatus {
  CONFIRMED_WITHIN_BUDGET(1),
  DECLARED_ESTIMATE(2),
  PRICE_UNKNOWN(3),
  CONFIRMED_OVER_BUDGET(4);

  private final int priority;

  PriceStatus(int priority) {
    this.priority = priority;
  }

  public int getPriority() {
    return priority;
  }
}
