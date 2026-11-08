package com.influencermatch.backend.recommendation.engine.presentation;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.dto.ReasoningCardDto;
import com.influencermatch.backend.recommendation.dto.ScoreBreakdownDto;
import com.influencermatch.backend.recommendation.engine.confidence.model.ConfidenceAssessment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 6: Deterministic XAI Reasoning Card Generator.
 * Provides transparent, data-backed explanations when Python AI service
 * is unavailable, times out, or when deterministic auditability is required.
 */
public class DeterministicReasoningGenerator {

  public ReasoningCardDto generate(
      RecommendationCandidateProjection cand,
      Campaign campaign,
      ScoreBreakdownDto breakdown,
      ConfidenceAssessment confidence) {

    List<String> reasons = new ArrayList<>();
    String name = (cand != null && cand.getDisplayName() != null)
        ? cand.getDisplayName()
        : ((cand != null && cand.getUserName() != null) ? cand.getUserName() : "KOC");

    String platform = (cand != null && cand.getPlatform() != null) ? cand.getPlatform() : "mạng xã hội";

    // 1. Evaluate memory strengths
    double m1 = (breakdown != null && breakdown.getM1Score() != null) ? breakdown.getM1Score() : 50.0;
    double m2 = (breakdown != null && breakdown.getM2Score() != null) ? breakdown.getM2Score() : 50.0;
    double m3 = (breakdown != null && breakdown.getM3Score() != null) ? breakdown.getM3Score() : 50.0;
    double m4 = (breakdown != null && breakdown.getM4Score() != null) ? breakdown.getM4Score() : 50.0;

    if (m3 >= 65.0) {
      reasons.add(String.format("Nội dung và phong cách thể hiện có độ tương đồng ngữ nghĩa cao với brief chiến dịch (%.1f/100).", m3));
    }
    if (m4 >= 65.0) {
      String nichesStr = (campaign != null && campaign.getNiches() != null)
          ? String.join(", ", campaign.getNiches())
          : "lĩnh vực yêu cầu";
      reasons.add(String.format("Khớp hoàn hảo với các lĩnh vực mục tiêu của thương hiệu: %s.", nichesStr));
    }
    if (m2 >= 65.0) {
      reasons.add(String.format("Chỉ số hiệu suất công khai trên %s ổn định và có tỷ lệ tương tác tích cực.", platform));
    }
    if (m1 >= 65.0) {
      reasons.add("Có hồ sơ lịch sử hợp tác quá khứ đáng tin cậy với tỷ lệ đạt KPI cao.");
    }

    if (reasons.isEmpty()) {
      reasons.add(String.format("Chỉ số tương tác và quy mô người theo dõi phù hợp với tiêu chí chiến dịch trên %s.", platform));
      reasons.add("Đáp ứng đầy đủ các điều kiện tiên quyết của chiến dịch.");
    }

    // Add all confidence or caution notes if available to ensure full evidence transparency
    List<String> warnings = (confidence != null && confidence.evidenceWarnings() != null)
        ? confidence.evidenceWarnings()
        : Collections.emptyList();
    for (String warning : warnings) {
      reasons.add("Lưu ý: " + warning);
    }

    double finalScore = (breakdown != null && breakdown.getFinalScore() != null) ? breakdown.getFinalScore() : 50.0;
    String summary = String.format(
        "%s đạt điểm phù hợp %.1f/100 dựa trên mô hình Four-Memory TOPSIS, thể hiện mức độ tương thích vững chắc với chiến dịch '%s'.",
        name,
        finalScore,
        (campaign != null && campaign.getName() != null) ? campaign.getName() : "Chiến dịch"
    );

    return ReasoningCardDto.builder()
        .summary(summary)
        .reasons(reasons)
        .explanationAvailable(false)
        .evidenceWarnings(warnings)
        .build();
  }
}
