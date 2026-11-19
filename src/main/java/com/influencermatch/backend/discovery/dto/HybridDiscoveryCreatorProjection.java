package com.influencermatch.backend.discovery.dto;

import java.math.BigDecimal;
import java.util.UUID;

public interface HybridDiscoveryCreatorProjection {
    UUID getId();
    UUID getCreatorId();
    UUID getMasterId();
    String getUserName();
    String getDisplayName();
    String getClaimStatus();
    String getPlatform();
    String getAvatarUrl();
    String getProfileUrl();
    Long getFollowers();
    BigDecimal getAvgViews();
    BigDecimal getAvgLikes();
    String getNiche();
    String getCategory();
    BigDecimal getEngagementRate();
    String getContentSummary();
    Double getSemanticScore();
    Double getFtsScore();
    Double getHybridScore();
    String getRetrievalMethod();
    String getLocation();
    default java.util.List<String> getExpansionReasons() { return java.util.List.of(); }
    default boolean getIsSimilarFallback() { return !getExpansionReasons().isEmpty(); }
}
