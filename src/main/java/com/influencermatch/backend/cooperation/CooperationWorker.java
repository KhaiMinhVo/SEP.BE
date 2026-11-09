package com.influencermatch.backend.cooperation;

import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.email.ClaimEmailCipher;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CooperationWorker {
  final CooperationDelivery delivery;
  final ClaimEmailCipher cipher;
  final VerificationEmailSender sender;

  @Async("claimEmailExecutor")
  public void sendAsync(UUID id) {
    process(id);
  }

  public void process(UUID id) {
    var work = delivery.prepare(id);
    if (work == null) return;
    boolean sent = false;
    try {
      var payload = cipher.decrypt(work.payload());
      sender.sendCooperationInvitation(payload.email(), payload.link());
      sent = true;
    } catch (Exception failure) {
      log.warn("Cooperation email attempt failed, jobId={}", id);
    }
    delivery.finish(work, sent);
  }
}
