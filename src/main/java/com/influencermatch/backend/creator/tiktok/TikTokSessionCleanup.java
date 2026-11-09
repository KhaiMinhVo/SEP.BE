package com.influencermatch.backend.creator.tiktok;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "application.tiktok.cleanup-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TikTokSessionCleanup {
  private final TikTokSessionStore store;

  @Scheduled(fixedDelay = 60000)
  public void cleanup() {
    store.expire();
  }
}
