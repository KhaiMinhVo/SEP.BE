package com.influencermatch.backend.recommendation.engine.memory;

import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 1: SM1 - Historical Campaign Performance Scorer.
 * Uses Bayesian Beta-Binomial smoothing on verified collaboration outcomes,
 * combined with objective-specific KPI achievement and brand review ratings.
 */
public class BayesianHistoryScorer {

  public static final double DEFAULT_PRIOR_MEAN = 0.50; // p0
  public static final double DEFAULT_PRIOR_STRENGTH = 3.0; // kappa

  public enum OutcomeClassification {
    SUCCESS,
    FAILURE,
    UNKNOWN
  }

  public record Result(
      double sm1Norm,
      double score100,
      double pHat,
      int sampleSize,
      int successCount,
      boolean isColdStart
  ) {}

  /**
   * Phân loại outcome duy nhất theo nguyên tắc Composite Collaboration Success:
   * - SUCCESS: Có bằng chứng thành công từ ROI >= 1.0, KPI đạt chỉ tiêu (có hỗ trợ lower-is-better), hoặc Review tích cực.
   * - FAILURE: Có kết quả nhưng ROI < 1.0, KPI trượt chỉ tiêu, hoặc Review tiêu cực.
   * - UNKNOWN: Hoàn toàn không có dữ liệu outcome lẫn review (không tính vào n và không tính vào s).
   */
  public OutcomeClassification classifyOutcome(HistoricalCampaign hist) {
    if (hist == null) {
      return OutcomeClassification.UNKNOWN;
    }

    boolean hasOutcomeData = false;
    boolean isPositiveRoiOrKpi = false;
    boolean isNegativeRoiOrKpi = false;

    // 1. Kiểm tra ROI hoặc KPI từ CampaignOutcome
    if (hist.getOutcome() != null) {
      CampaignOutcome outcome = hist.getOutcome();
      BigDecimal roi = outcome.getRoi();
      if (roi != null) {
        hasOutcomeData = true;
        if (roi.doubleValue() >= 1.0) {
          isPositiveRoiOrKpi = true;
        } else {
          isNegativeRoiOrKpi = true;
        }
      } else if (outcome.getKpiTarget() != null
          && outcome.getKpiActual() != null
          && outcome.getKpiTarget().doubleValue() > 0) {
        hasOutcomeData = true;
        double actual = outcome.getKpiActual().doubleValue();
        double target = outcome.getKpiTarget().doubleValue();
        String kpiType = outcome.getKpiType() != null ? outcome.getKpiType().trim().toUpperCase() : "";

        // Hỗ trợ cả nhóm chỉ số lower-is-better (ví dụ chi phí hoặc độ trễ)
        boolean isLowerBetter = kpiType.contains("COST")
            || kpiType.contains("CPA")
            || kpiType.contains("CPC")
            || kpiType.contains("CAC")
            || kpiType.contains("DELAY");

        if (isLowerBetter) {
          if (actual <= target) {
            isPositiveRoiOrKpi = true;
          } else {
            isNegativeRoiOrKpi = true;
          }
        } else {
          if (actual >= target) {
            isPositiveRoiOrKpi = true;
          } else {
            isNegativeRoiOrKpi = true;
          }
        }
      }
    }

    // 2. Kiểm tra Brand Review Ratings từ evidenceValue (JSON do CollaborationCompletedEventListener lưu)
    boolean hasReviewData = false;
    boolean isPositiveReview = false;
    boolean isNegativeReview = false;

    if (hist.getEvidenceValue() != null && !hist.getEvidenceValue().isNull()) {
      var evNode = hist.getEvidenceValue();
      if (evNode.has("reliabilityScore") && !evNode.get("reliabilityScore").isNull()) {
        hasReviewData = true;
        double r = evNode.get("reliabilityScore").asDouble();
        if (Double.isFinite(r)) {
          if (r >= 0.70) {
            isPositiveReview = true;
          } else if (r < 0.40) {
            isNegativeReview = true;
          }
        }
      } else if (evNode.has("overallRating") && !evNode.get("overallRating").isNull()) {
        hasReviewData = true;
        double rating = evNode.get("overallRating").asDouble();
        if (Double.isFinite(rating)) {
          if (rating >= 4.0) {
            isPositiveReview = true;
          } else if (rating <= 2.0) {
            isNegativeReview = true;
          }
        }
      }

      if (evNode.has("wouldCollaborateAgain") && !evNode.get("wouldCollaborateAgain").isNull()) {
        hasReviewData = true;
        if (evNode.get("wouldCollaborateAgain").asBoolean()) {
          isPositiveReview = true;
        }
      }
    }

    // THỨ TỰ ƯU TIÊN XÁC MINH (Evidence Precedence Contract):
    // 1. Nếu có dữ liệu KPI/ROI kinh doanh xác minh được: Ưu tiên tuyệt đối KPI/ROI.
    //    Review tích cực KHÔNG được phép đảo ngược kết luận khi KPI kinh doanh bị thất bại.
    if (hasOutcomeData) {
      return isPositiveRoiOrKpi ? OutcomeClassification.SUCCESS : OutcomeClassification.FAILURE;
    }

    // 2. Nếu không có KPI/ROI kinh doanh nhưng có Brand Review hợp lệ:
    //    Dùng Brand Review làm bằng chứng thay thế (Proxy Evidence) cho chất lượng hợp tác.
    if (hasReviewData) {
      if (isPositiveReview && !isNegativeReview) {
        return OutcomeClassification.SUCCESS;
      } else if (isNegativeReview) {
        return OutcomeClassification.FAILURE;
      }
    }

    // 3. Nếu không có bất kỳ bằng chứng nào đủ tin cậy -> UNKNOWN (không tăng n và s)
    return OutcomeClassification.UNKNOWN;
  }

  public Result score(List<HistoricalCampaign> pastCampaigns, String objective) {
    int evaluatedCount = 0; // n: Mẫu số Bayesian CHỈ đếm các campaign có outcome xác định (SUCCESS hoặc FAILURE)
    int successCount = 0;   // s: Tử số chỉ đếm các campaign SUCCESS
    double kpiSum = 0.0;
    int kpiCount = 0;
    double revSum = 0.0;
    int revCount = 0;

    if (pastCampaigns != null && !pastCampaigns.isEmpty()) {
      for (HistoricalCampaign hist : pastCampaigns) {
        if (hist == null) continue;

        OutcomeClassification classification = classifyOutcome(hist);
        if (classification == OutcomeClassification.SUCCESS) {
          evaluatedCount++;
          successCount++;
        } else if (classification == OutcomeClassification.FAILURE) {
          evaluatedCount++;
        }
        // Trường hợp UNKNOWN (thiếu dữ liệu kết quả): KHÔNG tăng evaluatedCount (tránh biến missing thành failure)

        // 1. Trích xuất KPI score nếu có
        if (hist.getOutcome() != null) {
          CampaignOutcome outcome = hist.getOutcome();
          BigDecimal roi = outcome.getRoi();
          if (roi != null) {
            double normRoi = Math.max(0.0, Math.min(1.0, roi.doubleValue() / 2.0));
            kpiSum += normRoi;
            kpiCount++;
          } else if (outcome.getKpiTarget() != null
              && outcome.getKpiActual() != null
              && outcome.getKpiTarget().doubleValue() > 0) {
            double actual = outcome.getKpiActual().doubleValue();
            double target = outcome.getKpiTarget().doubleValue();
            String kpiType = outcome.getKpiType() != null ? outcome.getKpiType().trim().toUpperCase() : "";
            boolean isLowerBetter = kpiType.contains("COST")
                || kpiType.contains("CPA")
                || kpiType.contains("CPC")
                || kpiType.contains("CAC")
                || kpiType.contains("DELAY");
            double normKpi = isLowerBetter
                ? (actual <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, target / actual)))
                : Math.max(0.0, Math.min(1.0, actual / target));
            kpiSum += normKpi;
            kpiCount++;
          }
        }

        // 2. Trích xuất Brand Review an toàn từ JSON
        if (hist.getEvidenceValue() != null && !hist.getEvidenceValue().isNull()) {
          var evNode = hist.getEvidenceValue();
          if (evNode.has("reliabilityScore") && !evNode.get("reliabilityScore").isNull()) {
            double r = evNode.get("reliabilityScore").asDouble();
            if (Double.isFinite(r)) {
              revSum += Math.max(0.0, Math.min(1.0, r));
              revCount++;
            }
          } else if (evNode.has("overallRating") && !evNode.get("overallRating").isNull()) {
            double rating = evNode.get("overallRating").asDouble();
            if (Double.isFinite(rating)) {
              revSum += Math.max(0.0, Math.min(1.0, rating / 5.0));
              revCount++;
            }
          }
        }
      }
    }

    boolean isColdStart = (evaluatedCount == 0);
    double pHat = isColdStart
        ? DEFAULT_PRIOR_MEAN
        : (successCount + DEFAULT_PRIOR_STRENGTH * DEFAULT_PRIOR_MEAN) / (evaluatedCount + DEFAULT_PRIOR_STRENGTH);

    double kpiScore = (kpiCount > 0) ? (kpiSum / kpiCount) : DEFAULT_PRIOR_MEAN;
    double revScore = (revCount > 0) ? (revSum / revCount) : DEFAULT_PRIOR_MEAN;

    // Internal weights tailored to campaign objective
    double wBayes = 0.30;
    double wKpi = 0.50;
    double wRev = 0.20;

    if (objective != null) {
      switch (objective.trim().toUpperCase()) {
        case "AWARENESS", "BRAND_AWARENESS" -> {
          wBayes = 0.40;
          wKpi = 0.40;
          wRev = 0.20;
        }
        case "ENGAGEMENT" -> {
          wBayes = 0.35;
          wKpi = 0.45;
          wRev = 0.20;
        }
        case "TRAFFIC" -> {
          wBayes = 0.35;
          wKpi = 0.45;
          wRev = 0.20;
        }
        case "CONVERSION", "CONVERSION_SALES", "SALES" -> {
          wBayes = 0.30;
          wKpi = 0.50;
          wRev = 0.20;
        }
        case "COMMUNITY_GROWTH" -> {
          wBayes = 0.35;
          wKpi = 0.40;
          wRev = 0.25;
        }
        default -> {
          // Standard default weights: wBayes=0.30, wKpi=0.50, wRev=0.20
        }
      }
    }

    double sm1Norm = (wBayes * pHat) + (wKpi * kpiScore) + (wRev * revScore);
    double score100 = sm1Norm * 100.0;

    return new Result(sm1Norm, score100, pHat, evaluatedCount, successCount, isColdStart);
  }
}
