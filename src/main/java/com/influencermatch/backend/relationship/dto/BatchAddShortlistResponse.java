package com.influencermatch.backend.relationship.dto;

import java.util.*;

public record BatchAddShortlistResponse(
    UUID campaignId, List<Result> results, Map<String, Long> counts, long totalShortlistCount) {
  public record Result(UUID creatorId, String action, ShortlistItemDto item, String errorCode) {}
}
