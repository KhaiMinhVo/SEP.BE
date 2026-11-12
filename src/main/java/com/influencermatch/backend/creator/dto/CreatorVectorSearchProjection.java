package com.influencermatch.backend.creator.dto;

import java.util.UUID;

public interface CreatorVectorSearchProjection {
  UUID getId();
  String getUserName();
  String getDisplayName();
  String getPlatform();
  String getAvatarUrl();
  String getProfileUrl();
  Double getSimilarity();
}
