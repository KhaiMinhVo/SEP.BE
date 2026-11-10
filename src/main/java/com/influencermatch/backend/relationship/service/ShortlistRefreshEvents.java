package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.relationship.dto.ShortlistCrawlEvent;
import java.util.UUID;

public final class ShortlistRefreshEvents {
  private ShortlistRefreshEvents() {}

  public record Requested(
      UUID campaignId, UUID creatorId, String platform, String username, String displayName) {}

  public record Completed(ShortlistCrawlEvent event) {}
}
