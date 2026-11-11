package com.influencermatch.backend.creator.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class DataQualityDtos {
  private DataQualityDtos() {}

  public record ApproveRequest(
      @Size(max = 120) @Pattern(regexp = "(?s).*\\S.*", message = "Niche must not be blank") String niche) {
    public ApproveRequest { niche = niche == null ? null : niche.trim(); }
  }

  public record QualityItem(UUID metricId, UUID creatorId, String platform, String userName,
      String displayName, String profileUrl, String niche, String contentSummary,
      BigDecimal dataConfidence, LocalDateTime collectedAt, UUID qualityReviewedBy,
      LocalDateTime qualityReviewedAt) {}
}
