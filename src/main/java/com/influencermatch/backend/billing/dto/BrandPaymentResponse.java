package com.influencermatch.backend.billing.dto;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record BrandPaymentResponse(UUID id, String transactionCode, String planName,
    BigDecimal amount, String currency, String gateway, PaymentStatus status,
    LocalDateTime createdAt, LocalDateTime paidAt, LocalDate startDate,
    LocalDate expirationDate, String failureReason) {}
