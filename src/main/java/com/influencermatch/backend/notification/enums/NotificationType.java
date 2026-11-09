package com.influencermatch.backend.notification.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Luồng 15.1: Centralized Notification Policy Matrix.
 * Automatically resolves whether a notification event triggers In-App only or In-App + Email dispatch.
 */
@Getter
@RequiredArgsConstructor
public enum NotificationType {
  // Collaboration Lifecycle (Luồng 8.1 & 8.2)
  COLLABORATION_PROPOSAL(true, "Lời mời hợp tác chiến dịch"),
  COLLABORATION_ACCEPTED(true, "Creator đồng ý hợp tác"),
  COLLABORATION_REJECTED(true, "Creator từ chối hợp tác"),
  CONTENT_DRAFT_SUBMITTED(true, "Creator nộp bản nháp nội dung"),
  CONTENT_CHANGE_REQUESTED(true, "Nhãn hàng yêu cầu chỉnh sửa bản nháp"),
  CONTENT_DRAFT_APPROVED(false, "Nhãn hàng đã duyệt bản nháp"),
  EVIDENCE_SUBMITTED(true, "Creator nộp bằng chứng kết quả bài đăng"),
  COLLABORATION_COMPLETED(false, "Nhãn hàng xác nhận nghiệm thu hợp tác"),

  // Creator Claim Profile (Luồng 4)
  CLAIM_APPROVED(true, "Yêu cầu quản lý kênh Creator được duyệt"),
  CLAIM_REJECTED(true, "Yêu cầu quản lý kênh Creator bị từ chối"),

  // Billing, Quota & Subscription (Luồng 12)
  PAYMENT_SUCCESS(true, "Xác nhận thanh toán gói cước thành công"),
  QUOTA_LOW(false, "Cảnh báo hạn ngạch dịch vụ sắp hết"),

  // Security & Account Admin (Luồng 13 & AD-01)
  ROLE_CHANGED(true, "Thông báo thay đổi vai trò tài khoản"),
  ACCOUNT_LOCKED(true, "Cảnh báo tài khoản bị tạm khóa");

  private final boolean emailDispatchEnabled;
  private final String defaultTitle;
}

