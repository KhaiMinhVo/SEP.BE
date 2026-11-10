package com.influencermatch.backend.recommendation.engine.phase0.enums;

/**
 * Đánh giá chất lượng dữ liệu và mức độ sẵn sàng chấm điểm của Creator:
 * Tách biệt hoàn toàn khỏi điều kiện Campaign.
 */
public enum ScoringReadiness {
  SCORE_READY,       // Dữ liệu hợp lệ, đầy đủ ER, VR, STAB. Sẵn sàng cho Bảng xếp hạng chính.
  PROVISIONAL,       // Dữ liệu hợp lệ, đầy đủ ER và VR, chỉ thiếu bằng chứng STAB (m < 2 hoặc views = 0). Đủ điều kiện chấm sơ bộ (evaluateSingle) với sàn bảo thủ STAB = 0.
  NOT_SCORABLE,      // Metric không khả dụng do loại nội dung/nền tảng (ví dụ bài ảnh không có Views) hoặc thiếu số liệu thiết yếu (không có Followers để tính ER, 0/0 views). Không phải lỗi dữ liệu.
  INVALID_DATA       // Dữ liệu bị lỗi toán học hoặc vi phạm hợp đồng schema (số âm, ER > 10.0, Infinity).
}

