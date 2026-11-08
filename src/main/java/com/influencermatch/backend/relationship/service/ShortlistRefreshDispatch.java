package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import com.influencermatch.backend.relationship.dto.ShortlistCrawlEvent;
import java.time.*;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
@lombok.extern.slf4j.Slf4j
public class ShortlistRefreshDispatch {
  private final CrawlerServiceClient crawler;
  private final ShortlistNotificationService notifications;
  private final Executor executor;
  private final Clock clock;

  public ShortlistRefreshDispatch(
      CrawlerServiceClient crawler,
      ShortlistNotificationService notifications,
      @Qualifier("shortlistRefreshExecutor") Executor executor,
      Clock clock) {
    this.crawler = crawler;
    this.notifications = notifications;
    this.executor = executor;
    this.clock = clock;
  }

  @Bean("shortlistRefreshExecutor")
  static ThreadPoolTaskExecutor executor() {
    var e = new ThreadPoolTaskExecutor();
    e.setCorePoolSize(2);
    e.setMaxPoolSize(2);
    e.setQueueCapacity(100);
    e.setThreadNamePrefix("shortlist-refresh-");
    return e;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void requested(ShortlistRefreshEvents.Requested event) {
    try {
      executor.execute(() -> send(event));
    } catch (org.springframework.core.task.TaskRejectedException ex) {
      report(event, false);
    }
  }

  private void send(ShortlistRefreshEvents.Requested e) {
    boolean accepted = false;
    try {
      crawler.requestShortlistCrawl(e.platform(), e.username(), e.creatorId(), e.campaignId());
      accepted = true;
    } catch (Exception ex) {
      log.warn(
          "Shortlist crawl request failed campaignId={} creatorId={} errorType={}",
          e.campaignId(),
          e.creatorId(),
          ex.getClass().getSimpleName());
    }
    report(e, accepted);
  }

  private void report(ShortlistRefreshEvents.Requested e, boolean accepted) {
    try {
      notifications.broadcast(
          e.campaignId(),
          ShortlistCrawlEvent.builder()
              .eventType(accepted ? "CRAWL_REQUESTED" : "CRAWL_REQUEST_FAILED")
              .campaignId(e.campaignId())
              .creatorId(e.creatorId())
              .creatorName(e.displayName())
              .platform(e.platform())
              .userName(e.username())
              .message(
                  accepted
                      ? "Refresh request accepted; data is not yet confirmed updated"
                      : "Refresh request could not be dispatched; reload existing data")
              .timestamp(LocalDateTime.ofInstant(clock.instant(), java.time.ZoneId.systemDefault()))
              .build());
    } catch (Exception ex) {
      log.warn(
          "Shortlist refresh notification unavailable campaignId={} creatorId={}",
          e.campaignId(),
          e.creatorId());
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void completed(ShortlistRefreshEvents.Completed event) {
    try {
      notifications.notifyCommittedCrawl(event.event());
    } catch (Exception ex) {
      log.warn(
          "Committed crawl notification unavailable creatorId={}", event.event().getCreatorId());
    }
  }
}
