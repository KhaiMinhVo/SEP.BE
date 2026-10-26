package com.influencermatch.backend.collaboration.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record EvidenceAssetResponse(
    UUID id,
    UUID collaborationId,
    UUID outcomeId,
    String assetType,
    String storageUrl,
    String source,
    LocalDateTime uploadedAt) {}
