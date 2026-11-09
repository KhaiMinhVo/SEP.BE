package com.influencermatch.backend.recommendation.engine.phase0.enums;

/**
 * Đánh giá điều kiện ràng buộc giữa Creator và Campaign:
 * Hoàn toàn độc lập với chất lượng dữ liệu của Creator.
 */
public enum CampaignEligibility {
  ELIGIBLE,              // Thỏa mãn toàn bộ ràng buộc cứng của Campaign (Platform, Negative Constraints, Account ACTIVE)
  INELIGIBLE,            // Vi phạm ràng buộc của Campaign (Sai platform, Bị loại trừ bởi Brand Feedback, Account SUSPENDED/BANNED)
  PENDING_VERIFICATION   // Thiếu thông tin bắt buộc để đối soát (Thiếu platform hoặc Chưa rõ trạng thái tài khoản UNKNOWN/NULL)
}
