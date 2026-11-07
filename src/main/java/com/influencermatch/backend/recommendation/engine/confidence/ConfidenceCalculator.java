package com.influencermatch.backend.recommendation.engine.confidence;

import com.influencermatch.backend.recommendation.engine.confidence.enums.ConfidenceTier;
import com.influencermatch.backend.recommendation.engine.confidence.model.ConfidenceAssessment;
import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.engine.memory.model.CandidateMemoryScores;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5: Recommendation Confidence Calculator.
 * Measures the empirical reliability of evidence supporting each memory layer
 * and aggregates them using criteria weights into a composite Confidence Index.
 * CRITICAL: Confidence NEVER alters the TOPSIS MatchScore; it serves purely as
 * an explainability and evidence transparency metric.
 */
public class ConfidenceCalculator {

  public ConfidenceAssessment calculate(CandidateMemoryScores scores, EngineWeights weights) {
    List<String> warnings = new ArrayList<>();

    // 1. Reliability for M1 (Historical Collaborations)
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
      warnings.add("KOC chưa có lịch sử hợp tác quá khứ trên nền tảng (Cold-start)");
    }

    // 2. Reliability for M2 (Public Intelligence)
    // Rule-based heuristic minh bạch phản ánh độ dày bằng chứng (Evidence Reliability):
    // >= 10 bài: 1.00 (mẫu dài hạn)
    // 3 - 9 bài: 0.65 (mẫu ngắn hạn crawler)
    // 2 bài: 0.45 (mẫu tối thiểu)
    // < 2 bài hoặc thiếu: 0.20 (bằng chứng yếu / chỉ số ổn định chưa khả dụng)
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
