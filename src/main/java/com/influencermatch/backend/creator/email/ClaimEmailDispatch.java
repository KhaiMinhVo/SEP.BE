package com.influencermatch.backend.creator.email;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="application.claim-email.dispatch-enabled",havingValue="true",matchIfMissing=true)
public class ClaimEmailDispatch {
  private final ClaimEmailWorker worker;
  private final ClaimEmailOutboxService outbox;
  @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
  public void committed(ClaimEmailOutboxService.Queued event){dispatch(event.id());}
  @Scheduled(fixedDelay=10000)
  public void sweep() {
    try {for(UUID id:outbox.due())dispatch(id);}
    catch(Exception error){log.warn("Claim email sweep unavailable; persisted jobs will be retried");}
  }
  private void dispatch(UUID id) {
    try {worker.sendAsync(id);}
    catch(TaskRejectedException rejected){log.warn("Claim email executor full; jobId={} remains recoverable",id);}
  }
}
