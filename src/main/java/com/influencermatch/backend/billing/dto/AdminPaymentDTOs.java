package com.influencermatch.backend.billing.dto;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class AdminPaymentDTOs {

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class AdminPaymentResponse {
    private UUID id;
    private String ref;
    private UUID brandId;
    private String brandName;
    private String userId;
    private String userName;
    private String userEmail;
    private String plan;
    private BigDecimal amount;
    private String currency;
    private String gateway;
    private PaymentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private String failureReason;
    private RefundDetails refund;
  }

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class PaymentStatsResponse {
    private BigDecimal totalRevenue;
    private BigDecimal totalRefunded;
    private BigDecimal totalPending;
    private long paidCount;
    private long pendingCount;
    private long refundedCount;
    private long failedCount;
    private long totalCount;
  }

  public record RefundDetails(com.influencermatch.backend.billing.enums.RefundStatus status,
      LocalDateTime requestedAt, LocalDateTime approvedAt, LocalDateTime completedAt,
      String reason, String decisionReason, String transferReference, String exceptionType,
      String gatewayRequestId, String gatewayCode, String gatewayTransactionNo) {
    public static RefundDetails from(com.influencermatch.backend.billing.model.Payment p) {
      return new RefundDetails(p.getRefundStatus(), p.getRefundRequestedAt(), p.getRefundApprovedAt(),
          p.getRefundedAt(), p.getRefundReason(), p.getRefundDecisionReason(),
          p.getRefundTransferReference(), p.getRefundExceptionType(), p.getGatewayRefundRequestId(),
          p.getGatewayRefundCode(), p.getGatewayRefundTransactionNo());
    }
  }

  @Getter @Setter @NoArgsConstructor @AllArgsConstructor
  public static class RefundRequest { private String reason; }

  public enum RefundExceptionType { DUPLICATE_PAYMENT, SYSTEM_ERROR }
  public record RefundApprovalRequest(String reason, RefundExceptionType exceptionType, boolean manual) {
    public RefundApprovalRequest(String reason, RefundExceptionType exceptionType) { this(reason, exceptionType, false); }
  }
  public record RefundConfirmationRequest(BigDecimal amount, String currency, String transferReference) {}
}
