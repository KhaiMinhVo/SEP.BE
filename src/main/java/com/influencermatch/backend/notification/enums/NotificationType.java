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
  COLLABORATION_RECORDED(
      true,
      "Hợp tác chiến dịch đã được ghi nhận",
      "Thỏa thuận hợp tác chiến dịch của bạn đã được ghi nhận thành công trên hệ thống.",
      OccurrencePolicy.SINGLE_PER_ENTITY),
  COOPERATION_INVITATION(
      true,
      "Lời mời hợp tác từ nhãn hàng",
      "Nhãn hàng đã gửi cho bạn một lời mời hợp tác chiến dịch.",
      OccurrencePolicy.SINGLE_PER_ENTITY),
  COLLABORATION_PROPOSAL(
      true,
      "Lời mời hợp tác chiến dịch",
      "Bạn nhận được lời mời tham gia chiến dịch hợp tác mới từ nhãn hàng.",
      OccurrencePolicy.SINGLE_PER_ENTITY),
  COLLABORATION_ACCEPTED(
      true,
      "Creator đồng ý hợp tác",
      "Creator đã đồng ý tham gia chiến dịch hợp tác.",
      OccurrencePolicy.SINGLE_PER_ENTITY),
  COLLABORATION_REJECTED(
      true,
      "Creator từ chối hợp tác",
      "Creator đã từ chối tham gia chiến dịch hợp tác.",
      OccurrencePolicy.SINGLE_PER_ENTITY),
  CONTENT_DRAFT_SUBMITTED(
      true,
      "Creator nộp bản nháp nội dung",
      "Creator đã gửi bản nháp nội dung cho chiến dịch. Vui lòng xem xét và phản hồi.",
      OccurrencePolicy.MULTI_EVENT_INSTANCE),
  CONTENT_CHANGE_REQUESTED(
      true,
      "Nhãn hàng yêu cầu chỉnh sửa bản nháp",
      "Nhãn hàng đã gửi yêu cầu chỉnh sửa cho bản nháp nội dung của bạn.",
      OccurrencePolicy.MULTI_EVENT_INSTANCE),
  CONTENT_DRAFT_APPROVED(
      false,
      "Nhãn hàng đã duyệt bản nháp",
      "Bản nháp nội dung của bạn đã được nhãn hàng phê duyệt thành công.",
      OccurrencePolicy.MULTI_EVENT_INSTANCE),
  EVIDENCE_SUBMITTED(
      true,
      "Creator nộp bằng chứng kết quả bài đăng",
      "Creator đã nộp bằng chứng nghiệm thu kết quả bài đăng cho chiến dịch.",
      OccurrencePolicy.MULTI_EVENT_INSTANCE),
  COLLABORATION_COMPLETED(
      false,
      "Nhãn hàng xác nhận nghiệm thu hợp tác",
      "Hợp tác chiến dịch đã được nhãn hàng nghiệm thu và hoàn tất thành công.",
      OccurrencePolicy.SINGLE_PER_ENTITY),

  // Creator Claim Profile (Luồng 4)
  CLAIM_APPROVED(
      true,
      "Yêu cầu quản lý kênh Creator được duyệt",
      "Yêu cầu nhận quyền quản lý kênh Creator của bạn đã được quản trị viên phê duyệt.",
      OccurrencePolicy.SINGLE_PER_ENTITY),
  CLAIM_REJECTED(
      true,
      "Yêu cầu quản lý kênh Creator bị từ chối",
      "Yêu cầu nhận quyền quản lý kênh Creator của bạn đã bị từ chối.",
      OccurrencePolicy.SINGLE_PER_ENTITY),

  // Billing, Quota & Subscription (Luồng 12)
  PAYMENT_SUCCESS(
      true,
      "Xác nhận thanh toán gói cước thành công",
      "Giao dịch thanh toán gói cước của bạn đã hoàn tất thành công.",
      OccurrencePolicy.SINGLE_PER_ENTITY),
  QUOTA_LOW(
      false,
      "Cảnh báo hạn ngạch dịch vụ sắp hết",
      "Hạn ngạch sử dụng của bạn sắp hết. Vui lòng nâng cấp gói để tiếp tục sử dụng dịch vụ.",
      OccurrencePolicy.SINGLE_PER_ENTITY),

  // Security & Account Admin (Luồng 13 & AD-01)
  ROLE_CHANGED(
      true,
      "Thông báo thay đổi vai trò tài khoản",
      "Vai trò tài khoản của bạn trên hệ thống đã được quản trị viên cập nhật thành công.",
      OccurrencePolicy.MULTI_EVENT_INSTANCE),
  ACCOUNT_LOCKED(
      true,
      "Cảnh báo tài khoản bị tạm khóa",
      "Tài khoản của bạn đã bị tạm khóa do vi phạm chính sách hoặc yêu cầu bảo mật.",
      OccurrencePolicy.MULTI_EVENT_INSTANCE);

  private final boolean emailDispatchEnabled;
  private final String defaultTitle;
  private final String defaultMessage;
  private final OccurrencePolicy occurrencePolicy;
}
