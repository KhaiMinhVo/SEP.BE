package com.influencermatch.backend.collaboration.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;

public record CreateCreatorReviewRequest(
    @NotNull @Min(1) @Max(5) @JsonDeserialize(using = StrictNumbers.IntegerValue.class)
        Integer overallRating,
    @NotNull @Min(1) @Max(5) @JsonDeserialize(using = StrictNumbers.IntegerValue.class)
        Integer brandFitRating,
    @NotNull @JsonDeserialize(using = StrictNumbers.BooleanValue.class)
        Boolean wouldCollaborateAgain,
    @Size(max = 2000) String note) {
  public CreateCreatorReviewRequest {
    note = note == null ? null : note.trim();
  }
}
