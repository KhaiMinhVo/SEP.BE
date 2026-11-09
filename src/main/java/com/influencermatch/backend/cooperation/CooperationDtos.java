package com.influencermatch.backend.cooperation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class CooperationDtos {
  private CooperationDtos() {}

  public record Create(@NotEmpty @Size(max = 100) List<@NotNull UUID> relationshipIds) {}

  public record Deliverable(
      @NotNull UUID creatorId,
      @NotBlank @Size(max = 100) String contentType,
      @Min(1)
          @Max(100)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using =
                  com.influencermatch.backend.collaboration.dto.StrictNumbers.IntegerValue.class)
          int quantity,
      @NotNull Instant dueAt,
      @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 500) String> acceptanceChecklist,
      @NotNull
          @Min(0)
          @Max(10)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using =
                  com.influencermatch.backend.collaboration.dto.StrictNumbers.IntegerValue.class)
          Integer maxRevisionRounds) {
    public Deliverable(UUID creatorId, String contentType, int quantity) {
      this(creatorId, contentType, quantity, null, null, null);
    }
  }

  public record Proposal(
      @Min(1) long expectedRevision,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2)
          BigDecimal fee,
      @NotNull @Pattern(regexp = "VND") String currency,
      @NotEmpty @Size(max = 100) List<@Valid Deliverable> deliverables,
      @NotBlank @Size(max = 4000) String terms,
      @Size(max = 20)
          List<com.influencermatch.backend.recommendation.learning.LearningKpis.Target>
              kpiTargets) {
    public Proposal {
      kpiTargets = kpiTargets == null ? List.of() : kpiTargets;
    }

    public Proposal(
        long expectedRevision,
        BigDecimal fee,
        String currency,
        List<Deliverable> deliverables,
        String terms) {
      this(expectedRevision, fee, currency, deliverables, terms, List.of());
    }
  }

  public record Revision(@Min(1) long expectedRevision) {}

  public record Send(@Min(1) long expectedRevision) {}

  public record Confirm(
      @Min(1) long expectedRevision,
      @NotBlank @Pattern(regexp = "PORTAL|EXTERNAL") String agreementSource,
      Instant agreedAt,
      @Size(max = 100) String evidenceSource,
      @Size(max = 2000) String evidenceUrl,
      @Size(max = 2000) String evidenceDescription) {}

  public record Cancel(@Min(1) long expectedRevision, @NotBlank @Size(max = 1000) String reason) {}

  public record Budget(
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2)
          BigDecimal totalBudget,
      @Pattern(regexp = "VND") @NotNull String currency) {}

  public record Batch(
      @NotEmpty @Size(max = 100) List<@NotNull UUID> creatorIds, @Size(max = 2000) String notes) {}
}
