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
}
