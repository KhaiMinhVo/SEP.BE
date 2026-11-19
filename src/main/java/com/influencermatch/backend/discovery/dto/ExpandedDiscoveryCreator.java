package com.influencermatch.backend.discovery.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Keep retrieval evidence intact while describing relaxed campaign preferences. */
public record ExpandedDiscoveryCreator(HybridDiscoveryCreatorProjection source,
        List<String> reasons) implements HybridDiscoveryCreatorProjection {
    public ExpandedDiscoveryCreator { reasons = List.copyOf(reasons); }
    public UUID getId() { return source.getId(); }
    public UUID getCreatorId() { return source.getCreatorId(); }
    public UUID getMasterId() { return source.getMasterId(); }
    public String getUserName() { return source.getUserName(); }
    public String getDisplayName() { return source.getDisplayName(); }
    public String getClaimStatus() { return source.getClaimStatus(); }
    public String getPlatform() { return source.getPlatform(); }
    public String getAvatarUrl() { return source.getAvatarUrl(); }
    public String getProfileUrl() { return source.getProfileUrl(); }
    public Long getFollowers() { return source.getFollowers(); }
    public BigDecimal getAvgViews() { return source.getAvgViews(); }
    public BigDecimal getAvgLikes() { return source.getAvgLikes(); }
    public String getNiche() { return source.getNiche(); }
    public String getCategory() { return source.getCategory(); }
    public BigDecimal getEngagementRate() { return source.getEngagementRate(); }
    public String getContentSummary() { return source.getContentSummary(); }
    public Double getSemanticScore() { return source.getSemanticScore(); }
    public Double getFtsScore() { return source.getFtsScore(); }
    public Double getHybridScore() { return source.getHybridScore(); }
    public String getRetrievalMethod() { return source.getRetrievalMethod(); }
    public String getLocation() { return source.getLocation(); }
    public List<String> getExpansionReasons() { return reasons; }
    public static String describe(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return "";
        return "Gần phù hợp — đã mở rộng: " + reasons.stream().map(reason -> switch (reason) {
            case "LOCATION" -> "địa điểm (khác hoặc chưa có thông tin)";
            case "FOLLOWERS" -> "quy mô follower";
            case "TOPIC" -> "chủ đề/ngành hàng liên quan";
            default -> reason;
        }).collect(java.util.stream.Collectors.joining(", ")) + ".";
    }
}
