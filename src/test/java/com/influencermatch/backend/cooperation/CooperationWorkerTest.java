package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.email.ClaimEmailCipher;
import java.util.*;
import org.junit.jupiter.api.Test;

class CooperationWorkerTest {
  @Test
  void smtpTimeoutFinishesAsFailureWithoutPropagatingProviderDetails() {
    var delivery = mock(CooperationDelivery.class);
    var cipher = mock(ClaimEmailCipher.class);
    var sender = mock(VerificationEmailSender.class);
    UUID id = UUID.randomUUID();
    var work = new CooperationDelivery.Work(id, UUID.randomUUID(), "encrypted");
    when(delivery.prepare(id)).thenReturn(work);
    when(cipher.decrypt("encrypted"))
        .thenReturn(
            new ClaimEmailCipher.Payload(
                "booking@example.test",
                "https://frontend.example/cooperation-invitations?token=private"));
    doThrow(new IllegalStateException("private SMTP response"))
        .when(sender)
        .sendCooperationInvitation(anyString(), anyString());
    assertThatCode(() -> new CooperationWorker(delivery, cipher, sender).process(id))
        .doesNotThrowAnyException();
    verify(delivery).finish(work, false);
  }

  @Test
  void replacedOrLeasedJobNeverCallsSmtp() {
    var delivery = mock(CooperationDelivery.class);
    var cipher = mock(ClaimEmailCipher.class);
    var sender = mock(VerificationEmailSender.class);
    UUID id = UUID.randomUUID();
    new CooperationWorker(delivery, cipher, sender).process(id);
    verifyNoInteractions(cipher, sender);
    verify(delivery, never()).finish(any(), anyBoolean());
  }

  @Test
  void rejectedAsyncDispatchRemainsRecoverableOnNextSweep() {
    var delivery = mock(CooperationDelivery.class);
    var worker = mock(CooperationWorker.class);
    UUID id = UUID.randomUUID();
    when(delivery.due()).thenReturn(List.of(id));
    doThrow(new org.springframework.core.task.TaskRejectedException("full"))
        .doNothing()
        .when(worker)
        .sendAsync(id);
    var dispatch = new CooperationDispatch(delivery, worker);
    assertThatCode(() -> dispatch.committed(new CooperationDelivery.Queued(id)))
        .doesNotThrowAnyException();
    dispatch.sweep();
    verify(worker, times(2)).sendAsync(id);
    verify(delivery, never()).finish(any(), anyBoolean());
  }
}
