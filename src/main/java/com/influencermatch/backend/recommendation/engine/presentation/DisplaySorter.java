package com.influencermatch.backend.recommendation.engine.presentation;

import com.influencermatch.backend.recommendation.engine.presentation.enums.DisplayMode;
import com.influencermatch.backend.recommendation.engine.presentation.enums.PriceStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6: Display Sorter.
 * Supports:
 * - BEST_MATCH: Sorted strictly by TOPSIS Match Score descending with deterministic tie-breaking.
 * - BUDGET_READY_FIRST: Bucketed by price status priority, then Match Score descending.
 */
public class DisplaySorter {

  public interface DisplayableItem {
    UUID creatorId();
    double matchScore();
    double sm3();
    double sm2();
    PriceStatus priceStatus();
  }

  public <T extends DisplayableItem> List<T> sort(List<T> items, DisplayMode mode) {
    if (items == null || items.isEmpty()) {
      return List.of();
    }

    List<T> sorted = new ArrayList<>(items);
    Comparator<T> comparator;

    if (mode == DisplayMode.BUDGET_READY_FIRST) {
      comparator = (a, b) -> {
        int pA = (a.priceStatus() != null) ? a.priceStatus().getPriority() : PriceStatus.PRICE_UNKNOWN.getPriority();
        int pB = (b.priceStatus() != null) ? b.priceStatus().getPriority() : PriceStatus.PRICE_UNKNOWN.getPriority();
        int cmpP = Integer.compare(pA, pB);
        if (cmpP != 0) return cmpP;

        int cmpScore = Double.compare(b.matchScore(), a.matchScore());
        if (cmpScore != 0) return cmpScore;

        int cmpSm3 = Double.compare(b.sm3(), a.sm3());
        if (cmpSm3 != 0) return cmpSm3;

        int cmpSm2 = Double.compare(b.sm2(), a.sm2());
        if (cmpSm2 != 0) return cmpSm2;

        return a.creatorId().compareTo(b.creatorId());
      };
    } else {
      // BEST_MATCH
      comparator = (a, b) -> {
        int cmpScore = Double.compare(b.matchScore(), a.matchScore());
        if (cmpScore != 0) return cmpScore;

        int cmpSm3 = Double.compare(b.sm3(), a.sm3());
        if (cmpSm3 != 0) return cmpSm3;

        int cmpSm2 = Double.compare(b.sm2(), a.sm2());
        if (cmpSm2 != 0) return cmpSm2;

        return a.creatorId().compareTo(b.creatorId());
      };
    }

    sorted.sort(comparator);
    return sorted;
  }
}
