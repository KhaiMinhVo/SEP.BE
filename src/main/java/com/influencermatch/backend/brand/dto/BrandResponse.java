package com.influencermatch.backend.brand.dto;

import com.influencermatch.backend.brand.Platform;
import java.time.LocalDateTime;
import java.util.*;

public record BrandResponse(
        UUID id, UUID userId, String businessName,
        String industry, List<String> productCategories, String website, String location,
        List<String> targetMarkets, List<String> targetAudiences, String brandTone,
        List<Platform> preferredPlatforms, String description, int m4Version,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {}
