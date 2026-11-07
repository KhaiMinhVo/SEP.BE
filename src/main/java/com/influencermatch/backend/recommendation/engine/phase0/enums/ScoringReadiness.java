package com.influencermatch.backend.recommendation.engine.phase0.enums;

/**
 * Đánh giá chất lượng dữ liệu và mức độ sẵn sàng chấm điểm của Creator:
 * Tách biệt hoàn toàn khỏi điều kiện Campaign.
 */
public enum ScoringReadiness {
  SCORE_READY,       // Dữ liệu hợp lệ, đủ bài đăng (m >= 2), đầy đủ ER*, VIEW*, STAB. Sẵn sàng cho Bảng xếp hạng chính.
  PROVISIONAL,       // Dữ liệu hợp lệ nhưng thiếu bằng chứng tối thiểu (m < 2 bài, thiếu STAB). Đánh giá sơ bộ/dự bị.
  INVALID_DATA       // Dữ liệu bị lỗi toán học hoặc vi phạm hợp đồng schema (số âm, ER > 1.0, không hợp lệ).
}
