package com.influencermatch.backend.creator.catalog;

import com.influencermatch.backend.creator.portal.PortalDtos.Verification;
import java.time.LocalDateTime;
import java.util.*;

public final class CatalogDtos {
  private CatalogDtos() {}

  public record Master(
      UUID id,
      UUID userId,
      String canonicalName,
      String status,
      LocalDateTime createdAt,
      long accountCount,
      Map<String, Long> verificationCounts) {}

  public record Account(
      UUID creatorId,
      UUID masterId,
      String platform,
      String externalId,
      String userName,
      String displayName,
      String sourceType,
      boolean isStableId,
      LocalDateTime createdAt,
      Verification verification) {}

  public record Event(
      UUID sourceId,
      String source,
      String eventType,
      LocalDateTime occurredAt,
      UUID actorId,
      UUID creatorId,
      UUID masterId,
      UUID claimId,
      String status,
      String reasonCode) {}
}
