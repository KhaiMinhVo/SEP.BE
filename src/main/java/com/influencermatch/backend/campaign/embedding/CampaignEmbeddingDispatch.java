package com.influencermatch.backend.campaign.embedding;

import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
@ConditionalOnProperty(name = "application.campaign-embedding.enabled", havingValue = "true")
public class CampaignEmbeddingDispatch {
  private final CampaignEmbeddingStore store;
  private final CampaignEmbeddingWorker worker;
  private final Executor executor;

  public CampaignEmbeddingDispatch(
      CampaignEmbeddingStore store,
      CampaignEmbeddingWorker worker,
      @Qualifier("campaignEmbeddingExecutor") Executor executor) {
    this.store = store;
    this.worker = worker;
    this.executor = executor;
  }

  @Bean("campaignEmbeddingExecutor")
  static ThreadPoolTaskExecutor executor() {
    var e = new ThreadPoolTaskExecutor();
    e.setCorePoolSize(2);
    e.setMaxPoolSize(2);
    e.setQueueCapacity(100);
    e.setThreadNamePrefix("campaign-embedding-");
    return e;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void committed(CampaignEmbeddingStore.Wake event) {
    submit(event.job());
  }

  @Scheduled(fixedDelay = 10000, initialDelay = 10000)
  public void sweep() {
    store.ensureCurrentJobs();
    store.due().forEach(this::submit);
  }

  private void submit(UUID id) {
    try {
      executor.execute(() -> worker.process(id));
    } catch (org.springframework.core.task.TaskRejectedException ignored) {
      /* durable job remains due */
    }
  }
}
