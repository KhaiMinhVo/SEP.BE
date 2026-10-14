package com.influencermatch.backend.discovery.dto;

import java.math.BigDecimal;
import java.util.UUID;

public interface HybridDiscoveryCreatorProjection {
    UUID getId();
    UUID getCreatorId();
    String getUserName();
    String getDisplayName();
    String getPlatform();
    String getAvatarUrl();
    String getProfileUrl();
    Long getFollowers();
    String getNiche();
    String getCategory();
    BigDecimal getEngagementRate();
    String getContentSummary();
    Double getSemanticScore();
    Double getFtsScore();
    Double getHybridScore();
}