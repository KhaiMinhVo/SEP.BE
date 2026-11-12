package com.influencermatch.backend.creator.email;

import com.influencermatch.backend.auth.service.VerificationEmailSender;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor @Slf4j
public class ClaimEmailWorker {
  private final ClaimEmailOutboxService outbox;
  private final ClaimEmailCipher cipher;
  private final VerificationEmailSender sender;
  @Async("claimEmailExecutor")
  public void sendAsync(UUID id){process(id);}
  /** Deliberately outside a transaction: only prepare/finish acquire database locks. */
  public void process(UUID id) {
    var work=outbox.prepare(id);if(work==null)return;
    boolean sent=false;
    try {
      var payload=cipher.decrypt(work.payloadCiphertext());
      sender.sendCreatorClaimInvitation(payload.email(),payload.link());sent=true;
    } catch(Exception error) {
      log.warn("Claim email attempt failed for jobId={}",id);
    }
    outbox.finish(work,sent);
  }
}
