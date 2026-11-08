package com.influencermatch.backend.recommendation.engine.phase0;

import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class EvidenceValidator {

  public record EvidenceMetrics(
      ErMethod erMethod,
      double rawEr,
      double rawVr,
      double rawStab,
      int postCount,
      boolean canComputeStability,
      boolean stabilityEvidenceUnavailable,
      double evidenceReliabilityM2,
      ScoringReadiness scoringReadiness,
      List<String> dataQualityReasons
  ) {
    public boolean isStabImputed() {
      return stabilityEvidenceUnavailable;
    }

    /**
     * Constructor tương thích ngược cho các bộ test và caller cũ.
     */
    public EvidenceMetrics(
        ErMethod erMethod,
        double rawEr,
        double rawVr,
        double rawStab,
        int postCount,
        boolean canComputeStability,
        boolean stabilityEvidenceUnavailable,
        double evidenceReliabilityM2
    ) {
      this(
          erMethod,
          rawEr,
          rawVr,
          rawStab,
          postCount,
          canComputeStability,
          stabilityEvidenceUnavailable,
          evidenceReliabilityM2,
          canComputeStability ? ScoringReadiness.SCORE_READY : ScoringReadiness.PROVISIONAL,
          canComputeStability ? List.of() : List.of("SHORT_WINDOW_INSUFFICIENT_POSTS")
      );
    }
  }

  /**
   * Xác định thời điểm đăng bài thực tế: ưu tiên postedAt (ISO-8601 từ social platform),
   * nếu không có thì fallback sang createdAt (thời điểm crawler lưu vào CSDL).
   */
  public static java.time.Instant resolvePostTime(Post p) {
    if (p == null) return java.time.Instant.EPOCH;
    if (p.getPostedAt() != null && !p.getPostedAt().isBlank()) {
      try {
        return java.time.Instant.parse(p.getPostedAt());
      } catch (Exception ignored) {
        try {
          return java.time.LocalDateTime.parse(p.getPostedAt())
              .atZone(java.time.ZoneId.systemDefault())
              .toInstant();
        } catch (Exception ignored2) {}
      }
    }
    if (p.getCreatedAt() != null) {
      return p.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant();
    }
    return java.time.Instant.EPOCH;
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

    // Kiểm tra Hợp đồng Dữ liệu ER: Decimal Ratio không âm và hữu hạn
    double dbEr = 0.0;
    if (cand.getEngagementRate() != null) {
      double v = cand.getEngagementRate().doubleValue();
      if (Double.isNaN(v) || Double.isInfinite(v) || v < 0.0) {
        throw new IllegalArgumentException("NEGATIVE_METRIC: engagementRate must be a non-negative finite number (" + v + ")");
      }
      if (v > ScoringPolicyConfig.ER_EXTREME_OUTLIER_THRESHOLD) {
        // Phân biệt Outlier Detection với Scale Validation: ER > 10.0 là dị thường cực đoan (1000% tương tác)
        throw new IllegalArgumentException(
            String.format("EXTREME_OUTLIER_ER: engagementRate exceeds sanity bound of 10.0 (%f).", v));
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
      // Khi có posts: ER tính trực tiếp từ bài đăng (luôn luôn là tỷ lệ thập phân chuẩn)
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

    // 4. Tính View Stability (STAB = 1 / (1 + CV)) trên CỬA SỔ 3 BÀI GẦN NHẤT
    // Sắp xếp theo ngày đăng thực tế (postedAt ưu tiên hơn createdAt).
    // Không nhảy cóc qua bài thiếu views sang bài cũ trong lịch sử để che giấu dữ liệu thiếu.
    double rawStab;
    boolean canComputeStability = false;
    boolean stabilityEvidenceUnavailable = false;
    double evidenceReliabilityM2;
    ScoringReadiness scoringReadiness;
    List<String> dataQualityReasons;

    List<Post> recentThreePosts = Collections.emptyList();
    if (posts != null && !posts.isEmpty()) {
      recentThreePosts = posts.stream()
          .filter(Objects::nonNull)
          .sorted((a, b) -> resolvePostTime(b).compareTo(resolvePostTime(a)))
          .limit(3)
          .toList();
    }

    // Lọc các bài có lượt xem hợp lệ trong chính cửa sổ 3 bài gần nhất
    List<Post> validWindowPosts = recentThreePosts.stream()
        .filter(p -> p.getViews() != null && p.getViews() >= 0)
        .toList();

    double stabAvgViews = 0.0;
    if (!validWindowPosts.isEmpty()) {
      stabAvgViews = validWindowPosts.stream()
          .mapToDouble(p -> p.getViews().doubleValue())
          .average()
          .orElse(0.0);
    }

    if (validWindowPosts.size() >= 2 && stabAvgViews > 0) {
      double varianceSum = 0.0;
      for (Post p : validWindowPosts) {
        double v = p.getViews().doubleValue();
        varianceSum += Math.pow(v - stabAvgViews, 2);
      }
      double sV = Math.sqrt(varianceSum / (validWindowPosts.size() - 1));
      double cv = sV / stabAvgViews;
      rawStab = 1.0 / (1.0 + cv);
      canComputeStability = true;
      stabilityEvidenceUnavailable = false;
      evidenceReliabilityM2 = (postCount >= 10) ? 1.00 : ((postCount >= 3) ? 0.65 : 0.45);
    } else {
      // Trong cửa sổ 3 bài gần nhất có ít hơn 2 bài có views hợp lệ HOẶC views = 0
      rawStab = Double.NaN;
      canComputeStability = false;
      stabilityEvidenceUnavailable = true;
      evidenceReliabilityM2 = 0.20; // Bằng chứng rất yếu
    }

    // ĐIỀU KIỆN SCORE_READY: Xác định dựa trên việc CẢ BA feature thực sự hợp lệ, không chỉ dựa trên postCount!
    boolean erValid = !Double.isNaN(rawEr) && !Double.isInfinite(rawEr) && rawEr >= 0.0;
    boolean vrValid = !Double.isNaN(rawVr) && !Double.isInfinite(rawVr) && rawVr >= 0.0;
    boolean stabValid = canComputeStability && !Double.isNaN(rawStab) && !Double.isInfinite(rawStab) && rawStab > 0.0;

    if (erValid && vrValid && stabValid) {
      scoringReadiness = ScoringReadiness.SCORE_READY;
      dataQualityReasons = List.of();
    } else {
      scoringReadiness = ScoringReadiness.PROVISIONAL;
      List<String> reasons = new ArrayList<>();
      if (!stabValid) {
        if (avgViews == 0.0 && postCount >= 2) {
          reasons.add("ZERO_VIEWS_FOR_STABILITY: Average views is 0 across posts, stability cannot be evaluated");
        } else {
          reasons.add(String.format("SHORT_WINDOW_INSUFFICIENT_POSTS: Post count %d < 2 for stability evaluation", postCount));
        }
      }
      if (!erValid) reasons.add("INVALID_ER_FEATURE");
      if (!vrValid) reasons.add("INVALID_VR_FEATURE");
      dataQualityReasons = Collections.unmodifiableList(reasons);
    }

    return new EvidenceMetrics(
        erMethod,
        rawEr,
        rawVr,
        rawStab,
        postCount,
        canComputeStability,
        stabilityEvidenceUnavailable,
        evidenceReliabilityM2,
        scoringReadiness,
        dataQualityReasons
    );
  }
}
