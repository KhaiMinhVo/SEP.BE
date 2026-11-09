package com.influencermatch.backend.brand.embedding;

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
@ConditionalOnProperty(name = "application.brand-embedding.enabled", havingValue = "true")
public class BrandEmbeddingDispatch {
  private final BrandEmbeddingStore store;
  private final BrandEmbeddingWorker worker;
  private final Executor executor;

  public BrandEmbeddingDispatch(
      BrandEmbeddingStore store,
      BrandEmbeddingWorker worker,
      @Qualifier("brandEmbeddingExecutor") Executor executor) {
    this.store = store;
    this.worker = worker;
    this.executor = executor;
  }

  @Bean("brandEmbeddingExecutor")
  static ThreadPoolTaskExecutor executor() {
    var e = new ThreadPoolTaskExecutor();
    e.setCorePoolSize(2);
    e.setMaxPoolSize(2);
    e.setQueueCapacity(100);
    e.setThreadNamePrefix("brand-embedding-");
    return e;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void committed(BrandEmbeddingStore.Wake event) {
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
