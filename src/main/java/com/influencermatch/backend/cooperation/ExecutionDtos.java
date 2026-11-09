package com.influencermatch.backend.cooperation;

import jakarta.validation.constraints.*;
import java.util.*;

public final class ExecutionDtos {
  private ExecutionDtos() {}

  public record Submission(
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long expectedRevision,
      @NotBlank @Size(max = 2000) String url,
      @NotBlank String source,
      @Size(max = 2000) String sourceDescription) {}

  public record Decision(
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long expectedRevision,
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long submissionRevision,
      @NotBlank @Pattern(regexp = "APPROVE|REQUEST_CHANGES") String decision,
      @NotNull @Size(max = 50) List<String> completedChecklist,
      @Size(max = 2000) String reason) {}

  public record Publication(
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long expectedRevision,
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long submissionRevision,
      @NotBlank @Size(max = 2000) String url,
      @Size(max = 100) String postId,
      @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using =
                  com.influencermatch.backend.collaboration.dto.StrictNumbers.BooleanValue.class)
          boolean acknowledgeException,
      @Size(max = 2000) String exceptionReason) {}

  public record Accept(
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long expectedRevision,
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long outcomeRevision) {}

  public record Reopen(
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long expectedRevision,
      @NotBlank @Size(max = 2000) String reason) {}

  public record FinalReview(
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long expectedRevision,
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long outcomeRevision,
      @NotNull
          @Min(1)
          @Max(5)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using =
                  com.influencermatch.backend.collaboration.dto.StrictNumbers.IntegerValue.class)
          Integer overallRating,
      @NotNull
          @Min(1)
          @Max(5)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using =
                  com.influencermatch.backend.collaboration.dto.StrictNumbers.IntegerValue.class)
          Integer brandFitRating,
      @NotNull
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using =
                  com.influencermatch.backend.collaboration.dto.StrictNumbers.BooleanValue.class)
          Boolean wouldCollaborateAgain,
      @Size(max = 2000) String note,
      @NotNull @Size(max = 20) List<@NotBlank @Size(max = 100) String> reviewTags) {}

  public record LegacyReview(
      @Min(1)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          long expectedRevision,
      @NotNull
          @Min(1)
          @Max(5)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using =
                  com.influencermatch.backend.collaboration.dto.StrictNumbers.IntegerValue.class)
          Integer overallRating,
      @NotNull
          @Min(1)
          @Max(5)
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using =
                  com.influencermatch.backend.collaboration.dto.StrictNumbers.IntegerValue.class)
          Integer brandFitRating,
      @NotNull
          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using =
                  com.influencermatch.backend.collaboration.dto.StrictNumbers.BooleanValue.class)
          Boolean wouldCollaborateAgain,
      @Size(max = 2000) String note,
      @NotNull @Size(max = 20) List<@NotBlank @Size(max = 100) String> reviewTags) {}
}
