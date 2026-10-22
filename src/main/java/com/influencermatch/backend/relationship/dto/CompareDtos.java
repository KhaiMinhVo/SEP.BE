package com.influencermatch.backend.relationship.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

public final class CompareDtos {
  private CompareDtos() {}
  public record Request(@NotNull @Size(min=2,max=5) List<@NotNull UUID> creatorIds) {}
  public record Response(UUID campaignId, List<Item> creators) {}
  public record Item(UUID creatorId, String platform, String userName, String displayName,
      String profileUrl, String avatarUrl, String location, String creatorType,
      ShortlistPriority priority, String note, CreatorSourceType sourceType,
      boolean verifiedByInfluencerMatch, LocalDateTime authorizedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Metrics metrics) {}
  public record Metrics(UUID metricId, String niche, Long followers, BigDecimal avgViews,
      BigDecimal avgLikes, BigDecimal avgComments, BigDecimal avgShares, BigDecimal engagementRate,
      BigDecimal dataConfidence, MetricDataStatus freshnessStatus, LocalDateTime collectedAt) {}
}
