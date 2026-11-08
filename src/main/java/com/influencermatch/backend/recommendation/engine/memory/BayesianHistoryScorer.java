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
    double revSum = 0.0;
    int revCount = 0;

    if (pastCampaigns != null && !pastCampaigns.isEmpty()) {
      for (HistoricalCampaign hist : pastCampaigns) {
        if (hist == null) continue;

        boolean verifiedSuccess = false;

        // 1. Kiểm tra ROI hoặc KPI từ CampaignOutcome (Hợp đồng đo lường hiệu năng)
        if (hist.getOutcome() != null) {
          BigDecimal roi = hist.getOutcome().getRoi();
          if (roi != null) {
            double roiVal = roi.doubleValue();
            if (roiVal >= 1.0) {
              verifiedSuccess = true;
            }
            double normRoi = Math.max(0.0, Math.min(1.0, roiVal / 2.0));
            kpiSum += normRoi;
            kpiCount++;
          } else if (hist.getOutcome().getKpiTarget() != null
              && hist.getOutcome().getKpiActual() != null
              && hist.getOutcome().getKpiTarget().doubleValue() > 0) {
            double actual = hist.getOutcome().getKpiActual().doubleValue();
            double target = hist.getOutcome().getKpiTarget().doubleValue();
            if (actual >= target) {
              verifiedSuccess = true;
            }
            double normKpi = Math.max(0.0, Math.min(1.0, actual / target));
            kpiSum += normKpi;
            kpiCount++;
          }
        }

        // 2. Trích xuất Brand Review Ratings thực tế từ evidenceValue (do CollaborationCompletedEventListener lưu)
        if (hist.getEvidenceValue() != null && !hist.getEvidenceValue().isNull()) {
          var evNode = hist.getEvidenceValue();
          if (evNode.has("reliabilityScore")) {
            double r = evNode.get("reliabilityScore").asDouble();
            revSum += Math.max(0.0, Math.min(1.0, r));
            revCount++;
            if (r >= 0.70) {
              verifiedSuccess = true; // Review tốt (>= 7/10) xác nhận thành công
            }
          } else if (evNode.has("overallRating")) {
            double rating = evNode.get("overallRating").asDouble();
            revSum += Math.max(0.0, Math.min(1.0, rating / 5.0));
            revCount++;
            if (rating >= 4.0) {
              verifiedSuccess = true;
            }
          }

          if (evNode.has("wouldCollaborateAgain") && evNode.get("wouldCollaborateAgain").asBoolean()) {
            verifiedSuccess = true;
          }
        }

        if (verifiedSuccess) {
          s++;
        }
      }
    }

    boolean isColdStart = (n == 0);
    double pHat = isColdStart
        ? DEFAULT_PRIOR_MEAN
        : (s + DEFAULT_PRIOR_STRENGTH * DEFAULT_PRIOR_MEAN) / (n + DEFAULT_PRIOR_STRENGTH);

    double kpiScore = (kpiCount > 0) ? (kpiSum / kpiCount) : DEFAULT_PRIOR_MEAN;
    double revScore = (revCount > 0) ? (revSum / revCount) : DEFAULT_PRIOR_MEAN;

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
