package com.influencermatch.backend.campaign;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.campaign.embedding.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "application.campaign-embedding.enabled=true")
class CampaignEmbeddingAfterCommitPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean CampaignEmbeddingWorker worker;
  @Autowired ApplicationEventPublisher events;
  @Autowired PlatformTransactionManager manager;

  @Autowired
  @Qualifier("campaignEmbeddingExecutor")
  org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor;

  @Test
  void commitDispatchesOnBoundedExecutorAndRollbackDoesNotDispatch() throws Exception {
    UUID committed = UUID.randomUUID(), rollback = UUID.randomUUID();
    var latch = new CountDownLatch(1);
    doAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(Thread.currentThread().getName()).startsWith("campaign-embedding-");
              latch.countDown();
              return null;
            })
        .when(worker)
        .process(committed);
    var tx = new TransactionTemplate(manager);
    tx.executeWithoutResult(
        s -> {
          events.publishEvent(new CampaignEmbeddingStore.Wake(committed));
          verify(worker, never()).process(committed);
        });
    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    tx.executeWithoutResult(
        s -> {
          events.publishEvent(new CampaignEmbeddingStore.Wake(rollback));
          s.setRollbackOnly();
        });
    assertThat(executor.getCorePoolSize()).isEqualTo(2);
    assertThat(executor.getMaxPoolSize()).isEqualTo(2);
    assertThat(executor.getQueueCapacity()).isEqualTo(100);
    verify(worker, never()).process(rollback);
  }
}
