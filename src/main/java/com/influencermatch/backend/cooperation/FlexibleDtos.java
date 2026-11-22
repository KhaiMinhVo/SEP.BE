package com.influencermatch.backend.cooperation;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.influencermatch.backend.collaboration.dto.StrictNumbers;

public final class FlexibleDtos {
  private FlexibleDtos() {}
  public record Mode(@Min(1) @JsonDeserialize(using=StrictNumbers.LongValue.class) long expectedRevision,
      @NotBlank @Pattern(regexp="COLLABORATIVE|BRAND_MANAGED") String managementMode) {}
  public record ExternalSubmission(@Min(1) @JsonDeserialize(using=StrictNumbers.LongValue.class) long expectedRevision,
      @NotBlank @Size(max=2000) String url, @NotBlank @Size(max=2000) String sourceDescription,
      @NotNull Instant receivedAt) {}
  public record ExternalPublication(@Min(1) @JsonDeserialize(using=StrictNumbers.LongValue.class) long expectedRevision,
      @Min(1) @JsonDeserialize(using=StrictNumbers.LongValue.class) long submissionRevision,
      @NotBlank @Size(max=2000) String url, @NotBlank @Size(max=2000) String sourceDescription,
      @NotNull Instant receivedAt) {}
  public record ExternalTermination(@Min(1) @JsonDeserialize(using=StrictNumbers.LongValue.class) long expectedRevision,
      @NotBlank @Size(max=2000) String reason, @NotNull Instant agreedAt,
      @NotBlank @Size(max=2000) String evidenceUrl) {}
  public record Withdrawal(@Min(1) @JsonDeserialize(using=StrictNumbers.LongValue.class) long expectedRevision) {}
  public record Exit(@Min(1) @JsonDeserialize(using=StrictNumbers.LongValue.class) long expectedRevision, @NotBlank @Size(max=2000) String reason,
      @Size(max=2000) String evidenceUrl, UUID requestId) {}
  public record Complete(@Min(1) @JsonDeserialize(using=StrictNumbers.LongValue.class) long expectedRevision,
      @Min(1) @JsonDeserialize(using=StrictNumbers.LongValue.class) long outcomeRevision) {}
}
