package com.influencermatch.backend.recommendation.engine.phase0.enums;

public enum ErMethod {
  BY_VIEWS,        // (Likes + Comments + Shares) / Views (Dành cho video / reels)
  BY_FOLLOWERS     // (Average Interactions per Post) / Followers (Dành cho bài ảnh / text)
}
