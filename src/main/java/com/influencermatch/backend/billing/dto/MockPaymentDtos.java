package com.influencermatch.backend.billing.dto;

import com.influencermatch.backend.billing.enums.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public final class MockPaymentDtos {
  private MockPaymentDtos() {}
  public enum Outcome { SUCCESS, FAILED }
  public record Request(@NotBlank @Size(max=255) String transactionCode, @NotNull Outcome outcome) {}
  public record Response(String transactionCode, PaymentStatus paymentStatus, UUID subscriptionId,
      SubscriptionStatus subscriptionStatus, LocalDate startDate, LocalDate expirationDate) {}
}
