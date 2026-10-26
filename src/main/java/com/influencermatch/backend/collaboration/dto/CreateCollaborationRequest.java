package com.influencermatch.backend.collaboration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

public record CreateCollaborationRequest(
    @NotNull UUID relationshipId,
    @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal agreedFee,
    @Pattern(regexp = "^[A-Z]{3}$") @Schema(example = "VND") String currency,
    @Size(max = 2000) String note) {
  public CreateCollaborationRequest {
    currency = currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(Locale.ROOT);
    note = note == null ? null : note.trim();
  }
}
