package com.influencermatch.backend.collaboration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

public record OutcomeResponse(
    UUID id,
    UUID collaborationId,
    Long views,
    Long clicks,
    Long likes,
    Long comments,
    Long shares,
    String publishedContentUrl,
    String resultNote,
    @Schema(
            description = "Brand-reported metrics; not independently verified",
            example = "SELF_REPORTED")
        String source,
    LocalDateTime recordedAt,
    LocalDateTime completedAt) {

  public OutcomeResponse(
      UUID id,
      UUID collaborationId,
      Long views,
      Long clicks,
      String publishedContentUrl,
      String resultNote,
      String source,
      LocalDateTime recordedAt,
      LocalDateTime completedAt) {
    this(
        id,
        collaborationId,
        views,
        clicks,
        null,
        null,
        null,
        publishedContentUrl,
        resultNote,
        source,
        recordedAt,
        completedAt);
  }
}
