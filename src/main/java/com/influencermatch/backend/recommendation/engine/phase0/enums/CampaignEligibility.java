package com.influencermatch.backend.recommendation.engine.phase0.enums;

/**
 * Đánh giá điều kiện ràng buộc giữa Creator và Campaign:
 * Hoàn toàn độc lập với chất lượng dữ liệu của Creator.
 */
public enum CampaignEligibility {
  ELIGIBLE,          // Thỏa mãn toàn bộ ràng buộc cứng của Campaign (Platform, Negative Constraints)
  INELIGIBLE,        // Vi phạm ràng buộc cứng của Campaign (Sai platform, Bị loại trừ bởi Brand Feedback)
  PENDING_PLATFORM   // Thiếu thông tin platform của Creator để đối soát với Campaign
}
