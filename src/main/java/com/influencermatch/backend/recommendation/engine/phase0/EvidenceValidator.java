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

    // 1. Xác định phương pháp tính ER và tính raw ER an toàn
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
      // Khi posts rỗng, kiểm tra an toàn trường engagementRate
      double dbEr = 0.0;
      if (cand.getEngagementRate() != null && cand.getEngagementRate().doubleValue() > 0) {
        double v = cand.getEngagementRate().doubleValue();
        // Kiểm soát scale: > 1.0 là % (vd 5.2%), <= 1.0 là thập phân (vd 0.052)
        dbEr = (v > 1.0) ? (v / 100.0) : v;
      }

      if (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0) {
        erMethod = ErMethod.BY_VIEWS;
        if (dbEr > 0) {
          rawEr = dbEr;
        } else {
          rawEr = (cand.getAvgLikes() != null) ? (cand.getAvgLikes().doubleValue() / cand.getAvgViews().doubleValue()) : 0.0;
        }
      } else if (followers > 0) {
        erMethod = ErMethod.BY_FOLLOWERS;
        if (dbEr > 0) {
          rawEr = dbEr;
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

    // 3. Tính View Stability (STAB = 1 / (1 + CV))
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
      // Heuristic cấu hình minh bạch: 3 bài ngắn hạn đạt 0.65, 2 bài tối thiểu đạt 0.45
      evidenceReliabilityM2 = (postCount >= 3) ? 0.65 : 0.45;
    } else {
      // Thiếu bài để tính mẫu số s_V -> Giữ Double.NaN, không tự gán số tùy ý che thiếu dữ liệu
      rawStab = Double.NaN;
      canComputeStability = false;
      isStabImputed = true;
      evidenceReliabilityM2 = 0.20; // Bằng chứng rất yếu
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
