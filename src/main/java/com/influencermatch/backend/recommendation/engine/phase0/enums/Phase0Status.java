package com.influencermatch.backend.recommendation.engine.phase0.enums;

public enum Phase0Status {
  ELIGIBLE,        // Vượt qua Hard Constraints và đủ bằng chứng dữ liệu
  INELIGIBLE,      // Vi phạm điều kiện cứng (loại khỏi tập ứng viên)
  PENDING_DATA     // Hợp lệ nhưng thiếu số lượng bài quan sát để đánh giá độ ổn định
}
