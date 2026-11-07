package com.influencermatch.backend.recommendation.engine.phase0;

import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import java.util.List;

public class EvidenceValidator {

  public record EvidenceMetrics(
      ErMethod erMethod,
      double rawEr,
      double rawVr,
      double rawStab,
      int postCount,
      boolean canComputeStability,
      boolean isStabImputed,
      double evidenceReliabilityM2
  ) {}

  public EvidenceMetrics evaluate(
      RecommendationCandidateProjection cand,
      List<Post> posts) {

    int postCount = posts != null ? posts.size() : 0;
    long followers = cand.getFollowers() != null ? cand.getFollowers() : 0L;

    double totalViews = 0.0;
    double totalInteractions = 0.0;

    if (posts != null && !posts.isEmpty()) {
      for (Post p : posts) {
        double v = p.getViews() != null ? p.getViews().doubleValue() : 0.0;
        totalViews += v;
        double likes = p.getLikes() != null ? p.getLikes().doubleValue() : 0.0;
        double comments = p.getComments() != null ? p.getComments().doubleValue() : 0.0;
        double shares = p.getShares() != null ? p.getShares().doubleValue() : 0.0;
        totalInteractions += (likes + comments + shares);
      }
    } else {
      // Fallback lấy từ public creator metric projection nếu danh sách post trống
      if (cand.getAvgViews() != null) totalViews = cand.getAvgViews().doubleValue();
      if (cand.getAvgLikes() != null) totalInteractions = cand.getAvgLikes().doubleValue();
    }

    // 1. Xác định phương pháp tính ER và tính raw ER
    ErMethod erMethod;
    double rawEr;

    if (posts != null && !posts.isEmpty()) {
      if (totalViews > 0) {
        erMethod = ErMethod.BY_VIEWS;
        rawEr = totalInteractions / totalViews;
      } else if (followers > 0) {
        erMethod = ErMethod.BY_FOLLOWERS;
        rawEr = (totalInteractions / postCount) / followers;
      } else {
        erMethod = ErMethod.BY_VIEWS;
        rawEr = 0.0;
      }
    } else {
      // Khi posts rỗng, ưu tiên trường engagementRate đã tổng hợp trong metric
      if (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0) {
        erMethod = ErMethod.BY_VIEWS;
        if (cand.getEngagementRate() != null && cand.getEngagementRate().doubleValue() > 0) {
          rawEr = cand.getEngagementRate().doubleValue() / 100.0;
        } else {
          rawEr = (cand.getAvgLikes() != null) ? (cand.getAvgLikes().doubleValue() / cand.getAvgViews().doubleValue()) : 0.0;
        }
      } else if (followers > 0) {
        erMethod = ErMethod.BY_FOLLOWERS;
        if (cand.getEngagementRate() != null && cand.getEngagementRate().doubleValue() > 0) {
          rawEr = cand.getEngagementRate().doubleValue() / 100.0;
        } else {
          rawEr = (cand.getAvgLikes() != null) ? (cand.getAvgLikes().doubleValue() / followers) : 0.0;
        }
      } else {
        erMethod = ErMethod.BY_VIEWS;
        rawEr = 0.0;
      }
    }

    // 2. Tính View-to-Follower Ratio (VR)
    double avgViews = (postCount > 0) ? (totalViews / postCount) : (cand.getAvgViews() != null ? cand.getAvgViews().doubleValue() : 0.0);
    double rawVr = (followers > 0 && avgViews > 0) ? (avgViews / followers) : 0.0;

    // 3. Tính View Stability (STAB)
    double rawStab;
    boolean canComputeStability = false;
    boolean isStabImputed = false;
    double evidenceReliabilityM2;

    if (posts != null && posts.size() >= 2 && avgViews > 0) {
      double varianceSum = 0.0;
      for (Post p : posts) {
        double v = p.getViews() != null ? p.getViews().doubleValue() : 0.0;
        varianceSum += Math.pow(v - avgViews, 2);
      }
      double sV = Math.sqrt(varianceSum / (posts.size() - 1));
      double cv = sV / avgViews;
      rawStab = 1.0 / (1.0 + cv);
      canComputeStability = true;
      isStabImputed = false;
      // Hiệu chuẩn độ tin cậy theo quy mô mẫu:
      // m >= 10: 1.00 (dài hạn), 3 <= m < 10: 0.65 (ngắn hạn sơ bộ), m = 2: 0.45 (tối thiểu)
      if (postCount >= 10) {
        evidenceReliabilityM2 = 1.00;
      } else if (postCount >= 3) {
        evidenceReliabilityM2 = 0.65;
      } else {
        evidenceReliabilityM2 = 0.45;
      }
    } else {
      // Thiếu bài để tính mẫu số s_V -> Không tự ý gán 0.50 tạo lợi thế giả, gán cận dưới thận trọng 0.25
      rawStab = 0.25;
      canComputeStability = false;
      isStabImputed = true;
      evidenceReliabilityM2 = 0.20; // Phạt bằng chứng để Phase 5 phản ánh đúng
    }

    return new EvidenceMetrics(
        erMethod,
        rawEr,
        rawVr,
        rawStab,
        postCount,
        canComputeStability,
        isStabImputed,
        evidenceReliabilityM2
    );
  }
}
