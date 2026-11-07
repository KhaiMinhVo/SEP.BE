package com.influencermatch.backend.recommendation.engine.phase0;

import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;
import java.util.ArrayList;
import java.util.Collections;
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
      double evidenceReliabilityM2,
      ScoringReadiness scoringReadiness,
      List<String> dataQualityReasons
  ) {
    /**
     * Constructor tương thích ngược không có 2 tham số mới.
     */
    public EvidenceMetrics(
        ErMethod erMethod,
        double rawEr,
        double rawVr,
        double rawStab,
        int postCount,
        boolean canComputeStability,
        boolean isStabImputed,
        double evidenceReliabilityM2
    ) {
      this(
          erMethod,
          rawEr,
          rawVr,
          rawStab,
          postCount,
          canComputeStability,
          isStabImputed,
          evidenceReliabilityM2,
          canComputeStability ? ScoringReadiness.SCORE_READY : ScoringReadiness.PROVISIONAL,
          canComputeStability ? List.of() : List.of("SHORT_WINDOW_INSUFFICIENT_POSTS")
      );
    }
  }

  public EvidenceMetrics evaluate(
      RecommendationCandidateProjection cand,
      List<Post> posts) {

    // 1. Kiểm tra tính hợp lệ dữ liệu cơ bản (Strict Data Contract Validation)
    long followers = cand.getFollowers() != null ? cand.getFollowers() : 0L;
    if (followers < 0L) {
      throw new IllegalArgumentException("NEGATIVE_METRIC: followers cannot be negative (" + followers + ")");
    }
    if (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() < 0) {
      throw new IllegalArgumentException("NEGATIVE_METRIC: avgViews cannot be negative (" + cand.getAvgViews() + ")");
    }
    if (cand.getAvgLikes() != null && cand.getAvgLikes().doubleValue() < 0) {
      throw new IllegalArgumentException("NEGATIVE_METRIC: avgLikes cannot be negative (" + cand.getAvgLikes() + ")");
    }

    // Kiểm tra Hợp đồng Dữ liệu ER: engagementRate phải là Decimal Ratio trong [0.0, 1.0]
    double dbEr = 0.0;
    if (cand.getEngagementRate() != null) {
      double v = cand.getEngagementRate().doubleValue();
      if (v < 0.0) {
        throw new IllegalArgumentException("NEGATIVE_METRIC: engagementRate cannot be negative (" + v + ")");
      }
      if (v > ScoringPolicyConfig.DATA_CONTRACT_ER_MAX) {
        // Tuyệt đối không tự suy đoán chia 100 bằng ngưỡng 1.0; ném lỗi vi phạm schema contract rõ ràng
        throw new IllegalArgumentException(
            String.format("INVALID_ER_SCALE: engagementRate exceeds 1.0 (%f). System contract requires decimal ratio in [0, 1]. E.g. 5.2%% must be stored as 0.0520.", v));
      }
      dbEr = v;
    }

    int postCount = posts != null ? posts.size() : 0;
    double totalViews = 0.0;
    double totalInteractions = 0.0;

    if (posts != null && !posts.isEmpty()) {
      for (Post p : posts) {
        double v = p.getViews() != null ? p.getViews().doubleValue() : 0.0;
        double likes = p.getLikes() != null ? p.getLikes().doubleValue() : 0.0;
        double comments = p.getComments() != null ? p.getComments().doubleValue() : 0.0;
        double shares = p.getShares() != null ? p.getShares().doubleValue() : 0.0;

        if (v < 0 || likes < 0 || comments < 0 || shares < 0) {
          throw new IllegalArgumentException("NEGATIVE_METRIC: post interaction metrics cannot be negative");
        }

        totalViews += v;
        totalInteractions += (likes + comments + shares);
      }
    } else {
      // Khi không có danh sách bài post, dùng các chỉ số tổng hợp
      if (cand.getAvgViews() != null) totalViews = cand.getAvgViews().doubleValue();
      if (cand.getAvgLikes() != null) totalInteractions = cand.getAvgLikes().doubleValue();
    }

    // 2. Xác định phương pháp tính ER và tính raw ER
    ErMethod erMethod;
    double rawEr;

    if (posts != null && !posts.isEmpty()) {
      // Khi có posts: ER tính trực tiếp từ bài đăng (luôn luôn là tỷ lệ thập phân chính xác tuyệt đối)
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
      // Khi không có posts: Dùng hợp đồng dữ liệu rõ ràng từ DB metric
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

    // 3. Tính View-to-Follower Ratio (VR)
    double avgViews = (postCount > 0) ? (totalViews / postCount) : (cand.getAvgViews() != null ? cand.getAvgViews().doubleValue() : 0.0);
    double rawVr = (followers > 0 && avgViews > 0) ? (avgViews / followers) : 0.0;

    // 4. Tính View Stability (STAB = 1 / (1 + CV))
    double rawStab;
    boolean canComputeStability = false;
    boolean isStabImputed = false;
    double evidenceReliabilityM2;
    ScoringReadiness scoringReadiness;
    List<String> dataQualityReasons;

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
      evidenceReliabilityM2 = (postCount >= 10) ? 1.00 : ((postCount >= 3) ? 0.65 : 0.45);
      scoringReadiness = ScoringReadiness.SCORE_READY;
      dataQualityReasons = List.of();
    } else {
      // Thiếu bài đăng để tính mẫu số s_V -> Giữ Double.NaN, phân loại PROVISIONAL
      rawStab = Double.NaN;
      canComputeStability = false;
      isStabImputed = true;
      evidenceReliabilityM2 = 0.20; // Bằng chứng rất yếu
      scoringReadiness = ScoringReadiness.PROVISIONAL;
      dataQualityReasons = List.of(
          String.format("SHORT_WINDOW_INSUFFICIENT_POSTS: Post count %d < 2; stability unavailable", postCount));
    }

    return new EvidenceMetrics(
        erMethod,
        rawEr,
        rawVr,
        rawStab,
        postCount,
        canComputeStability,
        isStabImputed,
        evidenceReliabilityM2,
        scoringReadiness,
        dataQualityReasons
    );
  }
}
