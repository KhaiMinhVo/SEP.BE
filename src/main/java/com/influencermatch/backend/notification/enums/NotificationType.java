package com.influencermatch.backend.notification.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Luồng 15.1: Centralized Notification Policy Matrix.
 * Automatically resolves whether a notification event triggers In-App only or In-App + Email dispatch,
 * and dictates whether dedup applies per business entity (Nhóm A) or per event instance (Nhóm B).
 */
@Getter
@RequiredArgsConstructor
public enum NotificationType {
  // Collaboration Lifecycle (Luồng 8.1 & 8.2)
  COLLABORATION_PROPOSAL(true, "Lời mời hợp tác chiến dịch", OccurrencePolicy.SINGLE_PER_ENTITY),
  COLLABORATION_ACCEPTED(true, "Creator đồng ý hợp tác", OccurrencePolicy.SINGLE_PER_ENTITY),
  COLLABORATION_REJECTED(true, "Creator từ chối hợp tác", OccurrencePolicy.SINGLE_PER_ENTITY),
  CONTENT_DRAFT_SUBMITTED(true, "Creator nộp bản nháp nội dung", OccurrencePolicy.MULTI_EVENT_INSTANCE),
  CONTENT_CHANGE_REQUESTED(true, "Nhãn hàng yêu cầu chỉnh sửa bản nháp", OccurrencePolicy.MULTI_EVENT_INSTANCE),
  CONTENT_DRAFT_APPROVED(false, "Nhãn hàng đã duyệt bản nháp", OccurrencePolicy.MULTI_EVENT_INSTANCE),
  EVIDENCE_SUBMITTED(true, "Creator nộp bằng chứng kết quả bài đăng", OccurrencePolicy.MULTI_EVENT_INSTANCE),
  COLLABORATION_COMPLETED(false, "Nhãn hàng xác nhận nghiệm thu hợp tác", OccurrencePolicy.SINGLE_PER_ENTITY),

  // Creator Claim Profile (Luồng 4)
  CLAIM_APPROVED(true, "Yêu cầu quản lý kênh Creator được duyệt", OccurrencePolicy.SINGLE_PER_ENTITY),
  CLAIM_REJECTED(true, "Yêu cầu quản lý kênh Creator bị từ chối", OccurrencePolicy.SINGLE_PER_ENTITY),

  // Billing, Quota & Subscription (Luồng 12)
  PAYMENT_SUCCESS(true, "Xác nhận thanh toán gói cước thành công", OccurrencePolicy.SINGLE_PER_ENTITY),
  QUOTA_LOW(false, "Cảnh báo hạn ngạch dịch vụ sắp hết", OccurrencePolicy.SINGLE_PER_ENTITY),

  // Security & Account Admin (Luồng 13 & AD-01)
  ROLE_CHANGED(true, "Thông báo thay đổi vai trò tài khoản", OccurrencePolicy.MULTI_EVENT_INSTANCE),
  ACCOUNT_LOCKED(true, "Cảnh báo tài khoản bị tạm khóa", OccurrencePolicy.MULTI_EVENT_INSTANCE);

  private final boolean emailDispatchEnabled;
  private final String defaultTitle;
  private final OccurrencePolicy occurrencePolicy;
}
