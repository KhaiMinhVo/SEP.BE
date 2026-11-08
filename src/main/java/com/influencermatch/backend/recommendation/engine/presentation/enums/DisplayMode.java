package com.influencermatch.backend.recommendation.engine.presentation.enums;

/**
 * Display ordering modes for presentation of recommendations:
 * - BEST_MATCH: Strictly ordered by TOPSIS MatchScore descending (no diversity reranking).
 * - BUDGET_READY_FIRST: Bucketed by price status priority (1..4), then MatchScore descending.
 * - DIVERSIFIED_MATCH: Relevance-first ranking with MMR diversity reranking.
 */
public enum DisplayMode {
  BEST_MATCH,
  BUDGET_READY_FIRST,
  DIVERSIFIED_MATCH
}
