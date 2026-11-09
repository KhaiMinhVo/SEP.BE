package com.influencermatch.backend.cooperation;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    name = "application.claim-email.dispatch-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CooperationDispatch {
  final CooperationDelivery delivery;
  final CooperationWorker worker;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void committed(CooperationDelivery.Queued event) {
    dispatch(event.id());
  }

  @Scheduled(fixedDelay = 10000)
  public void sweep() {
    try {
      for (UUID id : delivery.due()) dispatch(id);
    } catch (Exception failure) {
      log.warn("Cooperation email sweep unavailable; jobs remain recoverable");
    }
  }

  void dispatch(UUID id) {
    try {
      worker.sendAsync(id);
    } catch (TaskRejectedException full) {
      log.warn("Cooperation executor full; persisted job remains recoverable, jobId={}", id);
    }
  }
}
