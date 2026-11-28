package com.influencermatch.backend.recommendation.research.contracts;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned snapshot extension (research.extended_snapshot.v1) adding brandId,
 * explicit BrandPattern preferences, and ObservedCreatorContextExtension per creator
 * without mutating research.snapshot.v1.
 */
public record ExtendedCampaignSnapshot(
    String schemaVersion,
    CampaignCreatorSnapshot baseSnapshot,
    UUID brandId,
    List<BrandPatternSpec> brandPatterns,
    Map<UUID, ObservedCreatorContextExtension> contextExtensions
) {

  public static final String SCHEMA_VERSION = "research.extended_snapshot.v1";

  public record BrandPatternSpec(
      String niche,
      String category,
      String platform,
      double affinityScore
  ) {}

  public ExtendedCampaignSnapshot {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported ExtendedCampaignSnapshot schemaVersion: " + schemaVersion);
    }
    Objects.requireNonNull(baseSnapshot, "baseSnapshot must not be null");
    brandPatterns = brandPatterns != null ? List.copyOf(brandPatterns) : List.of();
    contextExtensions = contextExtensions != null ? Map.copyOf(contextExtensions) : Map.of();
  }
}
