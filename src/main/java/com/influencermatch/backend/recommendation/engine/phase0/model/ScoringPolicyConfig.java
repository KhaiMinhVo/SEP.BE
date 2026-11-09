package com.influencermatch.backend.recommendation.engine.phase0.model;

/**
 * Initial Calibration Parameters - MVP Version 1.0.
 * Lưu trữ các mốc điểm bán bão hòa (half-saturation K) làm căn cứ chuẩn hóa x / (x + K).
 */
public final class ScoringPolicyConfig {

  private ScoringPolicyConfig() {}

  public static final String CALIBRATION_VERSION = "v1.0-mvp";

  // 1. Tham số K cho ER theo Views (ER_VIEWS)
  public static final double K_ER_VIEWS_TIKTOK    = 0.050; // 5.0%
  public static final double K_ER_VIEWS_INSTAGRAM = 0.040; // 4.0%
  public static final double K_ER_VIEWS_FACEBOOK  = 0.025; // 2.5%

  // 2. Tham số K cho ER theo Followers (ER_FOLLOWERS)
  public static final double K_ER_FOLLOWERS_INSTAGRAM = 0.020; // 2.0%
  public static final double K_ER_FOLLOWERS_FACEBOOK  = 0.015; // 1.5%

  // 3. Tham số K cho View-to-Follower Ratio (VR)
  public static final double K_VR_TIKTOK    = 0.50; // 50% views/follower
  public static final double K_VR_INSTAGRAM = 0.35; // 35% views/follower
  public static final double K_VR_FACEBOOK  = 0.20; // 20% views/follower

  // Default fallback nội bộ cho các trường hợp đặc biệt
  public static final double DEFAULT_FALLBACK_K = 0.05;

  // 4. Hợp đồng dữ liệu hệ thống (System Data Contract)
  // engagementRate trong hệ thống được định nghĩa là tỷ lệ thập phân (Decimal Ratio), không âm và hữu hạn.
  // Ví dụ: 5.2% tương ứng 0.0520; 120% tương ứng 1.20 (tương tác vượt quá followers/views là hiện tượng thực tế).
  // Phân biệt Outlier Detection với Scale Validation:
  // Giá trị ER > 1.0 hoàn toàn hợp lệ và được nén tự nhiên qua hàm bão hòa x / (x + K).
  // Ngưỡng kiểm soát giá trị dị thường cực đoan (Extreme Outlier Sanity Bound): ER > 10.0 (1000% tương tác).
  public static final double ER_EXTREME_OUTLIER_THRESHOLD = 10.0;

  // 5. Missing-Feature Policy cho SM2 trong Bảng xếp hạng chính:
  // Định nghĩa chuẩn duy nhất cho SM2: SM2 = 1/3 ER* + 1/3 VIEW* + 1/3 STAB* (mẫu số luôn luôn là 3.0).
  // Khi Creator thiếu bài đăng đo độ ổn định (m < 2 bài), STAB áp dụng sàn bảo thủ (Conservative Policy Floor = 0.0).
  // Tuyệt đối không tự tiện chia 2 hoặc gán số giả (như 0.50 hay 0.25) làm biến dạng xếp hạng đa tiêu chí.
  public static final double STAB_MISSING_POLICY_FLOOR = 0.0;
}
