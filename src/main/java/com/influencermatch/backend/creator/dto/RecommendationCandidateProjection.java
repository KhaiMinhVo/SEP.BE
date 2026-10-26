package com.influencermatch.backend.creator.dto;

import java.math.BigDecimal;
import java.util.UUID;

public interface RecommendationCandidateProjection {
  UUID getId();
  UUID getCreatorId();
  UUID getMasterId();
  String getPlatform();
  String getDisplayName();
  String getUserName();
  String getAvatarUrl();
  Long getFollowers();
  BigDecimal getAvgViews();
  BigDecimal getAvgLikes();
  BigDecimal getEngagementRate();
  String getNiche();
  String getCategory();
  String getCreatorType();
  Double getSemanticScore();
}
