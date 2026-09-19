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

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PaymentStatus status;

  private LocalDateTime paidAt;
  private String failureReason;

  @Column(nullable = false)
  private LocalDateTime createdAt;
}
