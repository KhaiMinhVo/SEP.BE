package com.influencermatch.backend.creator.tiktok;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TikTokAccountBindingRepository extends JpaRepository<TikTokAccountBinding, UUID> {
  Optional<TikTokAccountBinding> findByEnvironmentAndClientKeyAndOpenId(
      String environment, String clientKey, String openId);

  Optional<TikTokAccountBinding> findByEnvironmentAndClientKeyAndCreatorId(
      String environment, String clientKey, UUID creatorId);
}
