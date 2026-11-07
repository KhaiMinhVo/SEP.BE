package com.influencermatch.backend.recommendation.engine.phase0;

import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;

public class PolicyNormalizer {

  /**
   * Tra cứu K_ER tương ứng với từng Platform và Phương pháp đo lường.
   * Tuyệt đối không fallback chéo từ TikTok sang Facebook.
   */
  public double resolveKEr(String platform, ErMethod method) {
    String p = platform != null ? platform.trim().toUpperCase() : "";
    if (method == ErMethod.BY_FOLLOWERS) {
      return switch (p) {
        case "FACEBOOK" -> ScoringPolicyConfig.K_ER_FOLLOWERS_FACEBOOK;
        case "INSTAGRAM" -> ScoringPolicyConfig.K_ER_FOLLOWERS_INSTAGRAM;
        default -> ScoringPolicyConfig.K_ER_FOLLOWERS_FACEBOOK;
      };
    } else {
      return switch (p) {
        case "TIKTOK" -> ScoringPolicyConfig.K_ER_VIEWS_TIKTOK;
        case "INSTAGRAM" -> ScoringPolicyConfig.K_ER_VIEWS_INSTAGRAM;
        case "FACEBOOK" -> ScoringPolicyConfig.K_ER_VIEWS_FACEBOOK;
        default -> ScoringPolicyConfig.DEFAULT_FALLBACK_K;
      };
    }
  }

  /**
   * Tra cứu K_VR theo nền tảng.
   */
  public double resolveKVr(String platform) {
    String p = platform != null ? platform.trim().toUpperCase() : "";
    return switch (p) {
      case "TIKTOK" -> ScoringPolicyConfig.K_VR_TIKTOK;
      case "INSTAGRAM" -> ScoringPolicyConfig.K_VR_INSTAGRAM;
      case "FACEBOOK" -> ScoringPolicyConfig.K_VR_FACEBOOK;
      default -> ScoringPolicyConfig.K_VR_TIKTOK;
    };
  }

  /**
   * Công thức biến đổi bão hòa: S = x / (x + K)
   * Yêu cầu x >= 0 và K > 0. Điểm kết quả luôn thuộc [0, 1).
   */
  public double normalize(double x, double k) {
    if (x <= 0.0 || k <= 0.0) {
      return 0.0;
    }
    return x / (x + k);
  }
}
