package com.influencermatch.backend.collaboration.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.URL;

public record RecordOutcomeRequest(
    @PositiveOrZero @JsonDeserialize(using = StrictNumbers.LongValue.class) Long views,
    @PositiveOrZero @JsonDeserialize(using = StrictNumbers.LongValue.class) Long clicks,
    @PositiveOrZero @JsonDeserialize(using = StrictNumbers.LongValue.class) Long likes,
    @PositiveOrZero @JsonDeserialize(using = StrictNumbers.LongValue.class) Long comments,
    @PositiveOrZero @JsonDeserialize(using = StrictNumbers.LongValue.class) Long shares,
    @Size(max = 1000) @URL(regexp = "(?i)^https?://.+") String publishedContentUrl,
    @Size(max = 2000) String resultNote) {
  public RecordOutcomeRequest {
    publishedContentUrl = publishedContentUrl == null ? null : publishedContentUrl.trim();
    resultNote = resultNote == null ? null : resultNote.trim();
  }

  public RecordOutcomeRequest(Long views, Long clicks, String publishedContentUrl, String resultNote) {
    this(views, clicks, null, null, null, publishedContentUrl, resultNote);
  }

  @JsonIgnore
  @Schema(hidden = true)
  @AssertTrue(message = "At least one metric (views, clicks, likes, comments, or shares) must be provided")
  public boolean isMetricsProvided() {
    return views != null || clicks != null || likes != null || comments != null || shares != null;
  }
}
