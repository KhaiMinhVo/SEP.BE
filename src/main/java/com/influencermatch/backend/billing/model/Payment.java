package com.influencermatch.backend.billing.model;

import com.influencermatch.backend.billing.controller.*;
import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.brand.model.BrandProfile;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(
    name = "payment",
    uniqueConstraints =
        @UniqueConstraint(name = "ux_payment_transaction_code", columnNames = "transaction_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private BrandProfile brandProfile;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(nullable = false)
  private Subscription subscription;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false, length = 100)
  private String paymentGateway;

  @Column(length = 255)
  private String transactionCode;

  @Column(length = 1000)
  private String gatewayPaymentUrl;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PaymentStatus status;

  private String gatewayCreateDate;
  private LocalDateTime lastReconciledAt;
  private LocalDateTime paidAt;
  private String failureReason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  @Builder.Default
  private RefundStatus refundStatus = RefundStatus.NONE;
  private LocalDateTime refundRequestedAt;
  private LocalDateTime refundApprovedAt;
  private LocalDateTime refundedAt;
  @Column(length = 1000)
  private String refundReason;
  @Column(length = 1000)
  private String refundDecisionReason;
  @Column(length = 255)
  private String refundTransferReference;
  @Column(length = 32)
  private String refundExceptionType;
  private UUID refundApprovedBy;
  private UUID refundCompletedBy;
  @Column(length = 32)
  private String gatewayRefundRequestId;
  @Column(length = 14)
  private String gatewayRefundCreateDate;
  private LocalDateTime gatewayRefundLastCheckedAt;
  @Column(length = 32)
  private String gatewayRefundCode;
  @Column(length = 32)
  private String gatewayRefundTransactionNo;

  @Column(nullable = false)
  private LocalDateTime createdAt;
}
