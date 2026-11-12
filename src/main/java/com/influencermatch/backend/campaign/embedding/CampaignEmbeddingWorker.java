package com.influencermatch.backend.campaign.embedding;

import com.influencermatch.backend.creator.client.AiServiceClient;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@lombok.extern.slf4j.Slf4j
@Component
@RequiredArgsConstructor
public class CampaignEmbeddingWorker {
  private final CampaignEmbeddingStore store;
  private final AiServiceClient ai;

  public void process(UUID id) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Embedding must run outside a database transaction");
    var lease = store.claim(id);
    if (lease == null) return;
    try {
      var vector = ai.getBrandEmbedding(lease.text(), lease.model());
      store.finish(lease, vector);
    } catch (Exception ex) {
      store.fail(lease);
      log.warn(
          "Campaign embedding attempt failed jobId={} attempt={}", lease.id(), lease.attempts());
    }
  }
}
