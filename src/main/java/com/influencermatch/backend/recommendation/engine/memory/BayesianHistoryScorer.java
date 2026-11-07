package com.influencermatch.backend.recommendation.engine.memory;

import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 1: SM1 - Historical Campaign Performance Scorer.
 * Uses Bayesian Beta-Binomial smoothing to handle sparse historical collaboration data,
 * combined with objective-specific KPI achievement and brand review ratings.
 */
public class BayesianHistoryScorer {

  public static final double DEFAULT_PRIOR_MEAN = 0.50; // p0
  public static final double DEFAULT_PRIOR_STRENGTH = 3.0; // kappa

  public record Result(
      double sm1Norm,
      double score100,
      double pHat,
      int sampleSize,
      int successCount,
      boolean isColdStart
  ) {}

  public Result score(List<HistoricalCampaign> pastCampaigns, String objective) {
    int n = (pastCampaigns != null) ? pastCampaigns.size() : 0;
    int s = 0;
    double kpiSum = 0.0;
    int kpiCount = 0;

    if (pastCampaigns != null && !pastCampaigns.isEmpty()) {
      for (HistoricalCampaign hist : pastCampaigns) {
        if (hist.getOutcome() != null) {
          BigDecimal roi = hist.getOutcome().getRoi();
          if (roi != null && roi.doubleValue() >= 1.0) {
            s++;
          }
          if (roi != null) {
            double normRoi = Math.max(0.0, Math.min(1.0, roi.doubleValue() / 2.0));
            kpiSum += normRoi;
            kpiCount++;
          }
        } else {
          s++; // Default completed collaboration without negative outcome
        }
      }
    }

    boolean isColdStart = (n == 0);
    double pHat = isColdStart
        ? DEFAULT_PRIOR_MEAN
        : (s + DEFAULT_PRIOR_STRENGTH * DEFAULT_PRIOR_MEAN) / (n + DEFAULT_PRIOR_STRENGTH);

    double kpiScore = (kpiCount > 0) ? (kpiSum / kpiCount) : DEFAULT_PRIOR_MEAN;
    double revScore = DEFAULT_PRIOR_MEAN; // Fallback prior for brand reviews

    // Internal weights tailored to campaign objective
    double wBayes = 0.30;
    double wKpi = 0.50;
    double wRev = 0.20;

    if (objective != null) {
      switch (objective.trim().toUpperCase()) {
        case "AWARENESS" -> {
          wBayes = 0.40;
          wKpi = 0.40;
          wRev = 0.20;
        }
        case "ENGAGEMENT" -> {
          wBayes = 0.35;
          wKpi = 0.45;
          wRev = 0.20;
        }
        case "CONVERSION" -> {
          wBayes = 0.30;
          wKpi = 0.50;
          wRev = 0.20;
        }
        default -> {
          // Standard default weights
        }
      }
    }

    double sm1Norm = (wBayes * pHat) + (wKpi * kpiScore) + (wRev * revScore);
    double score100 = sm1Norm * 100.0;

    return new Result(sm1Norm, score100, pHat, n, s, isColdStart);
  }
}
