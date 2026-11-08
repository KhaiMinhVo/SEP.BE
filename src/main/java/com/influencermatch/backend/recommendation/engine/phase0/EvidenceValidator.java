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

  public static final java.time.ZoneId VN_SOURCE_ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
  public static final java.time.ZoneId DEFAULT_SOURCE_ZONE = java.time.ZoneOffset.UTC;

  /**
   * Xác định thời điểm đăng bài thực tế: ưu tiên postedAt (ISO-8601 từ social platform),
   * nếu không có thì fallback sang createdAt (thời điểm crawler lưu vào CSDL).
   *
   * Quy tắc chuẩn hóa múi giờ:
   * 1. Nếu chuỗi có 'Z' hoặc offset rõ ràng (+07:00, -05:00,...): parse theo chính offset đó.
   * 2. Nếu chuỗi không có offset: sử dụng fallbackSourceZone được truyền vào theo hợp đồng dữ liệu của nguồn.
   *    Mặc định khi nguồn chưa xác nhận quy ước là UTC (không tự tiện giả định giờ Việt Nam).
   */
  public static java.time.Instant resolvePostTime(Post p) {
    return resolvePostTime(p, DEFAULT_SOURCE_ZONE);
  }

  public static java.time.Instant resolvePostTime(Post p, java.time.ZoneId fallbackSourceZone) {
    if (p == null) return java.time.Instant.EPOCH;
    java.time.ZoneId zone = fallbackSourceZone != null ? fallbackSourceZone : DEFAULT_SOURCE_ZONE;

    if (p.getPostedAt() != null && !p.getPostedAt().isBlank()) {
      String raw = p.getPostedAt().trim();
      try {
        return java.time.OffsetDateTime.parse(raw).toInstant();
      } catch (Exception ignored) {
        try {
          return java.time.LocalDateTime.parse(raw)
              .atZone(zone)
              .toInstant();
        } catch (Exception ignored2) {}
      }
    }
    if (p.getCreatedAt() != null) {
      return p.getCreatedAt().atZone(zone).toInstant();
    }
    return java.time.Instant.EPOCH;
  }

  /**
   * Chọn cửa sổ N bài đăng gần nhất theo thời điểm đăng thực tế (postedAt ưu tiên hơn createdAt).
   * Dùng chung một hàm duy nhất giữa Service và Validator, có tie-breaker theo Post ID để bảo đảm tính tất định.
   */
  public static List<Post> selectRecentWindow(List<Post> posts, int limit) {
    return selectRecentWindow(posts, limit, DEFAULT_SOURCE_ZONE);
  }

  public static List<Post> selectRecentWindow(List<Post> posts, int limit, java.time.ZoneId fallbackSourceZone) {
    if (posts == null || posts.isEmpty() || limit <= 0) return Collections.emptyList();
    return posts.stream()
        .filter(Objects::nonNull)
        .sorted((a, b) -> {
          int cmp = resolvePostTime(b, fallbackSourceZone).compareTo(resolvePostTime(a, fallbackSourceZone));
          if (cmp != 0) return cmp;
          if (a.getId() != null && b.getId() != null) {
            return b.getId().compareTo(a.getId());
          }
          return 0;
        })
        .limit(limit)
        .toList();
  }

  public EvidenceMetrics evaluate(
      RecommendationCandidateProjection cand,
      List<Post> posts) {
    return evaluate(cand, posts, DEFAULT_SOURCE_ZONE);
  }

  public EvidenceMetrics evaluate(
      RecommendationCandidateProjection cand,
      List<Post> posts,
      java.time.ZoneId fallbackSourceZone) {

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
    List<Post> postsWithViews = Collections.emptyList();
    double viewsSum = 0.0;
    boolean isFacebook = cand.getPlatform() != null && "FACEBOOK".equalsIgnoreCase(cand.getPlatform().trim());

    if (posts != null && !posts.isEmpty()) {
      if (isFacebook) {
        // Facebook: Nền tảng hướng người theo dõi (Follower-based platform), bài đăng thường là text/ảnh
        // Luôn tính ER theo Followers để đảm bảo tính nhất quán trên nền tảng Facebook
        erMethod = ErMethod.BY_FOLLOWERS;
        if (followers > 0) {
          rawEr = (totalInteractions / postCount) / followers;
        } else {
          rawEr = Double.NaN;
        }
      } else {
        // Các nền tảng video (TikTok, YouTube, Instagram Reels): Phân tách bài có views và bài không có views (bài ảnh)
        postsWithViews = posts.stream()
            .filter(p -> p.getViews() != null && p.getViews() >= 0)
            .toList();

        if (!postsWithViews.isEmpty()) {
          // Có bài đăng hỗ trợ Views: Chỉ tính ER trên chính tập bài có Views để tránh phóng đại tương tác
          erMethod = ErMethod.BY_VIEWS;
          viewsSum = postsWithViews.stream().mapToDouble(p -> p.getViews().doubleValue()).sum();
          double interactionsOnViewPosts = postsWithViews.stream().mapToDouble(p ->
              (p.getLikes() != null ? p.getLikes().doubleValue() : 0.0) +
              (p.getComments() != null ? p.getComments().doubleValue() : 0.0) +
              (p.getShares() != null ? p.getShares().doubleValue() : 0.0)
          ).sum();

          if (viewsSum > 0) {
            rawEr = interactionsOnViewPosts / viewsSum;
          } else {
            // viewsSum == 0: Tất cả video đều có 0 lượt xem thực tế
            // Phép chia 0 / 0 là bất định toán học (indeterminate form), không được tự ý gán 0.0
            rawEr = Double.NaN;
          }
        } else {
          // TẤT CẢ bài đều không có trường views (ví dụ bài ảnh Instagram photo)
          erMethod = ErMethod.BY_FOLLOWERS;
          if (followers > 0) {
            rawEr = (totalInteractions / postCount) / followers;
          } else {
            rawEr = Double.NaN; // Không có followers để chia
          }
        }
      }
    } else {
      // Khi không có posts:
      if (cand.getEngagementRate() != null) {
        // Giữ nguyên giá trị ER tổng hợp từ DB (kể cả 0.0 là quan sát thật)
        rawEr = cand.getEngagementRate().doubleValue();
        if (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0 && !isFacebook) {
          erMethod = ErMethod.BY_VIEWS;
        } else {
          erMethod = ErMethod.BY_FOLLOWERS;
        }
      } else {
        // cand.getEngagementRate() == null (DB không có ER tổng hợp)
        if (cand.getAvgLikes() != null) {
          if (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0 && !isFacebook) {
            erMethod = ErMethod.BY_VIEWS;
            rawEr = cand.getAvgLikes().doubleValue() / cand.getAvgViews().doubleValue(); // Like Rate proxy
          } else if (followers > 0) {
            erMethod = ErMethod.BY_FOLLOWERS;
            rawEr = cand.getAvgLikes().doubleValue() / followers;
          } else {
            erMethod = isFacebook ? ErMethod.BY_FOLLOWERS : ErMethod.BY_VIEWS;
            rawEr = Double.NaN;
          }
        } else {
          // Hoàn toàn không có dữ liệu để tính ER
          erMethod = isFacebook ? ErMethod.BY_FOLLOWERS : ErMethod.BY_VIEWS;
          rawEr = Double.NaN;
        }
      }
    }

    // 3. Tính View-to-Follower Ratio (VR)
    double rawVr;
    boolean unverifiedViewProvenance = false;
    if (posts != null && !posts.isEmpty()) {
      if (!postsWithViews.isEmpty() && !isFacebook) {
        double avgViews = viewsSum / postsWithViews.size();
        rawVr = (followers > 0) ? (avgViews / followers) : 0.0;
      } else if (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0 && !isFacebook) {
        // Fallback tạm thời: Dùng avgViews cấp kênh trong CSDL (lưu ý: chưa có metadata metricSource/metricWindow để xác minh cùng cửa sổ)
        rawVr = (followers > 0) ? (cand.getAvgViews().doubleValue() / followers) : 0.0;
        unverifiedViewProvenance = true;
      } else {
        // Không có bài nào hỗ trợ views (ví dụ bài ảnh hoặc Facebook không có video views)
        rawVr = Double.NaN;
      }
    } else if (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0 && !isFacebook) {
      rawVr = (followers > 0) ? (cand.getAvgViews().doubleValue() / followers) : 0.0;
    } else {
      rawVr = Double.NaN;
    }

    // 4. Tính View Stability (STAB = 1 / (1 + CV)) trên CỬA SỔ 3 BÀI GẦN NHẤT
    // Sử dụng chung hàm selectRecentWindow() với tie-breaker tất định theo Post ID và timezone nguồn được chỉ định.
    double rawStab;
    boolean canComputeStability = false;
    boolean stabilityEvidenceUnavailable = false;
    double evidenceReliabilityM2;
    ScoringReadiness scoringReadiness;
    List<String> dataQualityReasons;

    List<Post> recentThreePosts = selectRecentWindow(posts, 3, fallbackSourceZone);

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
      evidenceReliabilityM2 = (postCount >= 10) ? 1.00 : ((postCount >= 3) ? 0.65 : ((postCount == 2) ? 0.45 : 0.20));
    } else {
      // Trong cửa sổ 3 bài gần nhất có ít hơn 2 bài có views hợp lệ HOẶC views = 0
      rawStab = Double.NaN;
      canComputeStability = false;
      stabilityEvidenceUnavailable = true;
      evidenceReliabilityM2 = 0.20; // Bằng chứng rất yếu
    }

    if (unverifiedViewProvenance) {
      evidenceReliabilityM2 = 0.20; // Bằng chứng yếu do dùng fallback cấp kênh khác cửa sổ đo lường
    }

    // Kiểm tra chất lượng thời gian (Time Quality & Zone Verification) trên cửa sổ 3 bài gần nhất
    boolean hasUnverifiedTimezone = false;
    boolean hasCreatedAtFallback = false;
    boolean hasMalformedTimestamp = false;
    for (Post p : recentThreePosts) {
      if (p == null) continue;
      if (p.getPostedAt() == null || p.getPostedAt().isBlank()) {
        if (p.getCreatedAt() != null) {
          hasCreatedAtFallback = true;
        }
      } else {
        String raw = p.getPostedAt().trim();
        try {
          java.time.OffsetDateTime.parse(raw);
        } catch (Exception e1) {
          try {
            java.time.LocalDateTime.parse(raw);
            hasUnverifiedTimezone = true;
          } catch (Exception e2) {
            hasMalformedTimestamp = true;
            if (p.getCreatedAt() != null) {
              hasCreatedAtFallback = true;
            }
          }
        }
      }
    }

    List<String> timingWarnings = new ArrayList<>();
    if (hasUnverifiedTimezone) {
      timingWarnings.add("TIMEZONE_UNVERIFIED: Timestamp lacks offset; interpreted using Data Source Contract (" + (fallbackSourceZone != null ? fallbackSourceZone : DEFAULT_SOURCE_ZONE) + ")");
    }
    if (hasCreatedAtFallback) {
      timingWarnings.add("TIMESTAMP_FALLBACK_CREATED_AT: Post lacks postedAt; fell back to database createdAt");
    }
    if (hasMalformedTimestamp) {
      timingWarnings.add("TIMESTAMP_PARSE_FALLBACK: Malformed postedAt format; fell back to alternative timestamp");
    }

    // 5. Xác định Scoring Readiness: Phân biệt rõ rệt giữa SCORE_READY, PROVISIONAL, NOT_SCORABLE và INVALID_DATA
    boolean erValid = !Double.isNaN(rawEr) && !Double.isInfinite(rawEr) && rawEr >= 0.0;
    boolean vrValid = !Double.isNaN(rawVr) && !Double.isInfinite(rawVr) && rawVr >= 0.0;
    boolean stabValid = canComputeStability && !Double.isNaN(rawStab) && !Double.isInfinite(rawStab) && rawStab > 0.0;

    if (erValid && vrValid && stabValid) {
      scoringReadiness = ScoringReadiness.SCORE_READY;
      dataQualityReasons = Collections.unmodifiableList(timingWarnings);
    } else if (erValid && vrValid) {
      // ER và VR hợp lệ, CHỈ thiếu bằng chứng STAB (cửa sổ bài < 2 hoặc views = 0)
      scoringReadiness = ScoringReadiness.PROVISIONAL;
      List<String> reasons = new ArrayList<>();
      if (stabAvgViews == 0.0 && validWindowPosts.size() >= 2) {
        reasons.add("ZERO_VIEWS_FOR_STABILITY: Average views is 0 across posts, stability cannot be evaluated");
      } else {
        reasons.add(String.format("SHORT_WINDOW_INSUFFICIENT_POSTS: Valid posts in 3-post window %d < 2 for stability evaluation", validWindowPosts.size()));
      }
      if (unverifiedViewProvenance) {
        reasons.add("UNVERIFIED_AGGREGATE_VIEW_PROVENANCE: Channel avgViews used as fallback for photo content; measurement window unverified");
      }
      reasons.addAll(timingWarnings);
      dataQualityReasons = Collections.unmodifiableList(reasons);
    } else if (Double.isNaN(rawEr) || Double.isNaN(rawVr)) {
      // Metric không khả dụng do đặc thù loại nội dung (ví dụ bài ảnh không có Views để tính VR/STAB),
      // thiếu mẫu số (0/0), hoặc lỗi crawler chưa thu thập views cho video
      scoringReadiness = ScoringReadiness.NOT_SCORABLE;
      List<String> reasons = new ArrayList<>();
      if (Double.isNaN(rawEr)) {
        if (!postsWithViews.isEmpty() && viewsSum == 0.0) {
          reasons.add("ER_UNAVAILABLE_ZERO_VIEWS_DENOMINATOR: Video views sum is 0, 0/0 is mathematically indeterminate");
        } else if (postCount == 0 && cand.getEngagementRate() == null && cand.getAvgLikes() == null) {
          reasons.add("NO_POST_EVIDENCE: Creator has no posts in database to compute Engagement Rate");
        } else if (followers <= 0) {
          reasons.add("UNSUPPORTED_OR_MISSING_ER: Creator lacks follower count required as denominator for ER by followers");
        } else {
          reasons.add("UNSUPPORTED_OR_MISSING_ER: Engagement Rate cannot be calculated (lacks followers or interactions)");
        }
      }
      if (Double.isNaN(rawVr)) {
        if (postCount == 0 && (cand.getAvgViews() == null || cand.getAvgViews().doubleValue() <= 0)) {
          reasons.add("NO_POST_EVIDENCE: Creator has no posts in database and lacks aggregate view metrics");
        } else {
          String platformUpper = cand.getPlatform() != null ? cand.getPlatform().trim().toUpperCase() : "";
          boolean isVideoPlatform = "TIKTOK".equals(platformUpper) || "YOUTUBE".equals(platformUpper);
          if (isVideoPlatform) {
            reasons.add("MISSING_VIEW_METRICS: Crawler failed to collect view metrics for video content");
          } else {
            reasons.add("UNSUPPORTED_METRIC_VIEWS: Platform or content type does not provide Views required for VR and STAB in SM2");
          }
        }
      }
      reasons.addAll(timingWarnings);
      dataQualityReasons = Collections.unmodifiableList(reasons);
    } else {
      // Dữ liệu bị lỗi toán học (số âm, Infinite, vi phạm schema)
      scoringReadiness = ScoringReadiness.INVALID_DATA;
      List<String> reasons = new ArrayList<>();
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
