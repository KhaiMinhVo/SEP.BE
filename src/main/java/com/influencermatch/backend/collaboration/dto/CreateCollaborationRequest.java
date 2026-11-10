package com.influencermatch.backend.collaboration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

public record CreateCollaborationRequest(
    @NotNull UUID relationshipId,
    @Positive @Digits(integer = 17, fraction = 2) BigDecimal agreedFee,
    @Pattern(regexp = "^[A-Z]{3}$") @Schema(example = "VND") String currency,
    @Size(max = 2000) String note,
    UUID dealId,
    @Min(1) Long expectedRevision,
    @Pattern(regexp = "PORTAL|EXTERNAL") String agreementSource,
    java.time.Instant agreedAt,
    @Size(max = 100) String evidenceSource,
    @Size(max = 2000) String evidenceUrl,
    @Size(max = 2000) String evidenceDescription) {
  public CreateCollaborationRequest(
      UUID relationshipId, BigDecimal agreedFee, String currency, String note) {
    this(relationshipId, agreedFee, currency, note, null, null, null, null, null, null, null);
  }

  public CreateCollaborationRequest {
    currency =
        currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(Locale.ROOT);
    note = note == null ? null : note.trim();
  }
}
