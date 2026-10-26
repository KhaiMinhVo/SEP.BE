package com.influencermatch.backend.creator.meta;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "application.meta", name = "cleanup-enabled", havingValue = "true")
public class MetaSessionCleanup {
  private final MetaSessionStore store;

  @Scheduled(fixedDelay = 60000)
  public void expire() {
    store.expire();
  }
}
