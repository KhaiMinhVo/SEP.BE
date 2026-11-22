package com.influencermatch.backend.storage;

import java.time.LocalDateTime;

/**
 * Metadata record for media stored in cloud storage (Cloudinary).
 */
public record StoredMedia(
    String url,
    String storageKey,
    String mimeType,
    long sizeBytes,
    Integer width,
    Integer height,
    LocalDateTime updatedAt
) {}
