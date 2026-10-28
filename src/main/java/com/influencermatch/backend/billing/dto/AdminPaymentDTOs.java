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

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class RefundRequest {
    private String reason;
  }
}
