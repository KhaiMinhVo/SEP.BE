package com.influencermatch.backend.creator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PostMetricsDto {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AuthorDto(
      String externalId,
      String username,
      String displayName
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MetricsDto(
      Long views,
      Long likes,
      Long comments,
      Long shares,
      BigDecimal engagementRate
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ContentDto(
      String caption,
      String thumbnailUrl,
      String postedAt
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CollectionDto(
      String source,
      String method,
      Boolean cacheHit,
      String scope,
      String engagementRateFormula,
      String engagementRateUnit
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ErrorDetailDto(
      String code,
      String message,
      Boolean retryable,
      Integer retryAfterSeconds
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PostMetricsResponse(
      String schemaVersion,
      String status,
      String platform,
      String postUrl,
      String canonicalPostUrl,
      String postId,
      AuthorDto author,
      MetricsDto metrics,
      Map<String, String> metricStatus,
      Map<String, Object> metricErrors,
      ContentDto content,
      String trackedAt,
      String servedAt,
      CollectionDto collection,
      List<String> warnings,
      ErrorDetailDto error
  ) {
    public boolean isSuccess() {
      return "success".equalsIgnoreCase(status);
    }

    public boolean isMetricAvailable(String metricName) {
      if (metricStatus == null) {
        return false;
      }
      return "AVAILABLE".equalsIgnoreCase(metricStatus.get(metricName));
    }

    public Long getSafeViews() {
      return isMetricAvailable("views") && metrics != null ? metrics.views() : null;
    }

    public Long getSafeLikes() {
      return isMetricAvailable("likes") && metrics != null ? metrics.likes() : null;
    }

    public Long getSafeComments() {
      return isMetricAvailable("comments") && metrics != null ? metrics.comments() : null;
    }

    public Long getSafeShares() {
      return isMetricAvailable("shares") && metrics != null ? metrics.shares() : null;
    }
  }
}
