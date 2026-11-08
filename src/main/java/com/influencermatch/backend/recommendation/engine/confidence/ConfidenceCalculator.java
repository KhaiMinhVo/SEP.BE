package com.influencermatch.backend.recommendation.engine.confidence;

import com.influencermatch.backend.recommendation.engine.confidence.enums.ConfidenceTier;
import com.influencermatch.backend.recommendation.engine.confidence.model.ConfidenceAssessment;
import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.engine.memory.model.CandidateMemoryScores;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5: Recommendation Confidence Calculator.
 * Measures the Campaign-weighted Evidence Availability and Coverage across all four memory layers:
 * Conf_i = sum_j (w_j * r_ij) in [0.20, 1.00].
 *
 * Construct Definition:
 * Confidence measures whether required data inputs for evaluation are fully available and verifiable
 * (e.g. verified campaign outcomes, active post stability window, pgvector embedding, campaign niches),
 * weighted by the specific Campaign Objective's priorities (W).
 * It is NOT an empirical performance guarantee or quality score.
 *
 * CRITICAL: Confidence NEVER alters the TOPSIS MatchScore; it serves purely as
 * an explainability, evidence coverage, and risk transparency metric for Marketers.
 */
public class ConfidenceCalculator {

  public ConfidenceAssessment calculate(CandidateMemoryScores scores, EngineWeights weights) {
    List<String> warnings = new ArrayList<>();

    // 1. Evidence Availability for M1 (Historical Collaborations with Verified Outcomes)
    // Heuristic saturation threshold (MVP policy):
    // n >= 5: 1.00 (Mẫu lịch sử dày dặn)
    // 2 - 4: 0.80 (Mẫu lịch sử trung bình)
    // n == 1: 0.50 (Mẫu tối thiểu)
    // n == 0: 0.20 (Chưa có kết quả xác minh / Cold-start)
    // Lưu ý: Outcome UNKNOWN hoàn toàn không được tính vào n.
    double r1;
    int n = scores.m1SampleSize();
    if (n >= 5) {
      r1 = 1.00;
    } else if (n >= 2) {
      r1 = 0.80;
    } else if (n == 1) {
      r1 = 0.50;
    } else {
      r1 = 0.20;
      warnings.add("Chưa có kết quả chiến dịch quá khứ được xác minh trên nền tảng (Cold-start)");
    }

    // 2. Evidence Availability for M2 (Public Intelligence & Stability)
    // Rule-based heuristic đồng bộ với Phase 0 Evidence Policy:
    // >= 10 bài: 1.00 (Mẫu dài hạn / kho lưu trữ lịch sử mở rộng)
    // 3 - 9 bài: 0.65 (Mẫu snapshot crawler chuẩn hiện tại: cửa sổ 3 bài gần nhất)
    // 2 bài: 0.45 (Mẫu tối thiểu tính độ lệch chuẩn N-1)
    // < 2 bài hoặc thiếu: 0.20 (Bằng chứng yếu / chỉ số ổn định chưa khả dụng hoặc ước lượng sàn)
    double r2;
    if (scores.m2PostCount() >= 10 && !scores.m2StabImputed()) {
      r2 = 1.00;
    } else if (scores.m2PostCount() >= 3 && !scores.m2StabImputed()) {
      r2 = 0.65;
    } else if (scores.m2PostCount() == 2 && !scores.m2StabImputed()) {
      r2 = 0.45;
    } else {
      r2 = 0.20;
      warnings.add("Số lượng bài đăng hạn chế (chỉ số ổn định chưa khả dụng hoặc ước lượng)");
    }

    // 3. Reliability for M3 (Semantic Relevance)
    double r3;
    if (scores.m3EmbeddingAvailable()) {
      r3 = 1.00;
    } else {
      r3 = 0.30;
      warnings.add("Chưa có vector nội dung chi tiết");
    }

    // 4. Reliability for M4 (Brand Affinity)
    double r4;
    if (scores.m4BrandNichesConfigured()) {
      r4 = 1.00;
    } else {
      r4 = 0.50;
      warnings.add("Chiến dịch chưa cấu hình lĩnh vực chi tiết");
    }

    // 5. Composite Confidence: Conf_i = sum(w_j * r_ij)
    double composite = (weights.w1() * r1)
                     + (weights.w2() * r2)
                     + (weights.w3() * r3)
                     + (weights.w4() * r4);

    ConfidenceTier tier;
    if (composite >= 0.75) {
      tier = ConfidenceTier.HIGH;
    } else if (composite >= 0.50) {
      tier = ConfidenceTier.MEDIUM;
    } else {
      tier = ConfidenceTier.LOW;
    }

    return new ConfidenceAssessment(tier, composite, r1, r2, r3, r4, warnings);
  }
}
