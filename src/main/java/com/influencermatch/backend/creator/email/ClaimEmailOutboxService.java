package com.influencermatch.backend.creator.email;

import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.enums.Role;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
public class ClaimEmailOutboxService {
  public record Queued(UUID id) {}
  public record Delivery(String status,int attempts,LocalDateTime nextAttemptAt,LocalDateTime lastSentAt,boolean canRetry) {}
  public record Work(UUID id,UUID leaseToken,String payloadCiphertext) {}
  private final ClaimEmailJobRepository jobs;
  private final CreatorInvitationRepository invitations;
  private final CreatorRepository creators;
  private final VerificationEmailSender sender;
  private final ClaimEmailCipher cipher;
  private final Clock clock;
  private final ApplicationEventPublisher events;
  @PersistenceContext private EntityManager em;
  private LocalDateTime now(){return LocalDateTime.now(clock);}
  public void requireConfigured(){sender.requireConfigured();cipher.requireConfigured();}

  @Transactional(propagation=Propagation.MANDATORY)
  public void enqueue(CreatorInvitation invitation,String link) {
    requireConfigured();
    var job=jobs.save(ClaimEmailJob.builder().invitation(invitation).status("QUEUED").tokenHash(invitation.getTokenHash())
        .payloadCiphertext(cipher.encrypt(invitation.getInvitedEmail(),link)).expiresAt(invitation.getExpiresAt()).nextAttemptAt(now()).build());
    events.publishEvent(new Queued(job.getId()));
  }
  @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
  public List<UUID> due(){return jobs.findDue(now(),PageRequest.of(0,100));}

  private CreatorInvitation lockInvitation(UUID id) {
    var initial=invitations.findById(id).orElseThrow(()->new NotFoundException("Invitation not found"));
    var creator=creators.findLockedById(initial.getTargetCreator().getId()).orElseThrow(()->new NotFoundException("Creator not found"));
    em.refresh(creator);
    var invitation=invitations.findLockedById(id).orElseThrow(()->new NotFoundException("Invitation not found"));
    em.refresh(invitation);
    return invitation;
  }
  private boolean valid(ClaimEmailJob job,CreatorInvitation invitation) {
    var creator=invitation.getTargetCreator();
    return "PENDING".equals(invitation.getStatus()) && invitation.getExpiresAt().isAfter(now()) && job.getExpiresAt().isAfter(now())
        && invitation.getTokenHash().equals(job.getTokenHash()) && creator.getMaster()==null
        && creator.getSourceType()!=CreatorSourceType.AUTHORIZED;
  }
  private void terminate(ClaimEmailJob job,String status) {
    job.setStatus(status);job.setPayloadCiphertext(null);job.setNextAttemptAt(null);job.setLeaseToken(null);job.setLeaseUntil(null);
  }
  @Transactional(propagation=Propagation.REQUIRES_NEW)
  public Work prepare(UUID id) {
    var initial=jobs.findById(id).orElse(null);if(initial==null)return null;
    var invitation=lockInvitation(initial.getInvitation().getId());
    var job=jobs.lockById(id).orElseThrow();em.refresh(job);
    if(job.getPayloadCiphertext()==null)return null;
    // Never invalidate an unexpired lease: another worker may currently be sending SMTP.
    if("PROCESSING".equals(job.getStatus()) && job.getLeaseUntil().isAfter(now()))return null;
    if(!valid(job,invitation)) {
      terminate(job,invitation.getExpiresAt().isAfter(now()) && job.getExpiresAt().isAfter(now())?"CANCELLED":"EXPIRED");return null;
    }
    boolean due="QUEUED".equals(job.getStatus()) && !job.getNextAttemptAt().isAfter(now());
    boolean abandoned="PROCESSING".equals(job.getStatus()) && !job.getLeaseUntil().isAfter(now());
    if(!due&&!abandoned)return null;
    if(job.getCycleAttempts()>=5) {job.setStatus("FAILED");job.setLeaseToken(null);job.setLeaseUntil(null);job.setNextAttemptAt(null);return null;}
    job.setStatus("PROCESSING");job.setAttempts(job.getAttempts()+1);job.setCycleAttempts(job.getCycleAttempts()+1);
    job.setLastAttemptAt(now());job.setNextAttemptAt(null);job.setLeaseToken(UUID.randomUUID());job.setLeaseUntil(now().plusMinutes(2));
    return new Work(job.getId(),job.getLeaseToken(),job.getPayloadCiphertext());
  }
  @Transactional(propagation=Propagation.REQUIRES_NEW)
  public void finish(Work work,boolean sent) {
    var job=jobs.lockById(work.id()).orElseThrow();em.refresh(job);
    if(!"PROCESSING".equals(job.getStatus()) || !work.leaseToken().equals(job.getLeaseToken()) || !job.getLeaseUntil().isAfter(now()))return;
    if(sent) {job.setLastSentAt(now());terminate(job,"SENT");return;}
    job.setLeaseToken(null);job.setLeaseUntil(null);
    if(job.getCycleAttempts()>=5){job.setStatus("FAILED");job.setNextAttemptAt(null);}
    else {job.setStatus("QUEUED");job.setNextAttemptAt(now().plusSeconds(15L << (job.getCycleAttempts()-1)));}
  }
  private CreatorInvitation owned(UUID id) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    var actor=Permissions.actor();
    if(actor.getRole()!=Role.BRAND)throw new ForbiddenException("Only Brands may manage claim email");
    return invitations.findById(id).filter(i->i.getBrand()!=null && i.getBrand().getUser().getId().equals(actor.getId()))
        .orElseThrow(()->new NotFoundException("Invitation not found"));
  }
  private boolean retryAllowed(ClaimEmailJob job,CreatorInvitation invitation) {
    return "FAILED".equals(job.getStatus()) && job.getPayloadCiphertext()!=null && valid(job,invitation)
        && !job.getLastAttemptAt().plusSeconds(60).isAfter(now())
        && job.getManualRetryTimes().stream().filter(t->t.isAfter(now().minusHours(1))).count()<5;
  }
  @Transactional(readOnly=true)
  public Delivery status(UUID invitationId) {
    var invitation=owned(invitationId);var job=jobs.findByInvitationId(invitationId).orElse(null);
    return job==null?new Delivery("NOT_TRACKED",0,null,null,false):delivery(job,invitation);
  }
  private Delivery delivery(ClaimEmailJob job,CreatorInvitation invitation) {
    return new Delivery(job.getStatus(),job.getAttempts(),job.getNextAttemptAt(),job.getLastSentAt(),retryAllowed(job,invitation));
  }
  @Transactional
  public Delivery retry(UUID invitationId) {
    owned(invitationId);
    var invitation=lockInvitation(invitationId);
    // Recheck ownership after acquiring locks, before accessing the job.
    owned(invitationId);
    var initial=jobs.findByInvitationId(invitationId).orElseThrow(()->new ConflictException(ErrorCode.CLAIM_EMAIL_NOT_RETRYABLE,"No recoverable email job"));
    var job=jobs.lockById(initial.getId()).orElseThrow();em.refresh(job);
    if(!"FAILED".equals(job.getStatus()) || job.getPayloadCiphertext()==null || !valid(job,invitation))
      throw new ConflictException(ErrorCode.CLAIM_EMAIL_NOT_RETRYABLE,"Claim email cannot be retried");
    if(!retryAllowed(job,invitation))throw new BusinessException(ErrorCode.CLAIM_EMAIL_RATE_LIMITED,"Claim email retry limit exceeded");
    requireConfigured();
    job.getManualRetryTimes().removeIf(t->!t.isAfter(now().minusHours(1)));
    job.getManualRetryTimes().add(now());job.setCycleAttempts(0);job.setStatus("QUEUED");job.setNextAttemptAt(now());
    events.publishEvent(new Queued(job.getId()));return delivery(job,invitation);
  }
}
