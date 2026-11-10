package com.influencermatch.backend.cooperation;

import static com.influencermatch.backend.cooperation.DealStore.*;

import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.email.ClaimEmailCipher;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class CooperationDelivery {
  public record Queued(UUID id) {}

  public record Work(UUID id, UUID token, String payload) {}

  final DealStore store;
  final ClaimEmailCipher cipher;
  final VerificationEmailSender sender;
  final ApplicationEventPublisher events;

  @Value("${application.auth.creator-invitation.frontend-base-url:http://localhost:5173}")
  String frontend;

  static final Pattern EMAIL =
      Pattern.compile(
          "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}");

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> enqueue(Map<String, Object> d, Map<String, Object> p) {
    UUID id = uuid(d.get("id"));
    long revision = ((Number) p.get("revision")).longValue();
    if (store
        .optional(
            "select id from cooperation_invitation where deal_id=? and proposal_revision=? and"
                + " status='PENDING' and expires_at>?",
            id,
            revision,
            store.time())
        .isPresent()) throw conflict("Current proposal already has a live invitation");
    String email = null;
    UUID owner = null;
    if (d.get("master_id") != null) {
      var u =
          store.one(
              "select u.id,u.email,u.role,u.status,u.email_verified from creator_master m join"
                  + " \"user\" u on u.id=m.user_id where m.id=?",
              d.get("master_id"));
      if (!"CREATOR".equals(u.get("role"))
          || !"ACTIVE".equals(u.get("status"))
          || !Boolean.TRUE.equals(u.get("email_verified")))
        throw conflict("Recipient account is not available");
      owner = uuid(u.get("id"));
      email = u.get("email").toString();
    } else {
      UUID channel = uuid(store.members(id).getFirst().get("creator_id"));
      var metric =
          store.optional(
              "select contact from public_creator_metric where creator_id=? order by collected_at"
                  + " desc,id desc limit 1",
              channel);
      if (metric.isPresent() && metric.get().get("contact") != null) {
        var matcher = EMAIL.matcher(metric.get().get("contact").toString());
        if (matcher.find()) email = matcher.group();
      }
    }
    cipher.requireConfigured();
    if (email != null) sender.requireConfigured();
    var base = java.net.URI.create(frontend);
    if (!Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getRawQuery() != null
        || base.getRawFragment() != null)
      throw new BusinessException(
          ErrorCode.EMAIL_DELIVERY_UNAVAILABLE, "Cooperation frontend URL is unavailable");
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    UUID invitation = UUID.randomUUID(), job = UUID.randomUUID();
    String link = frontend.replaceAll("/+$", "") + "/cooperation-invitations?token=" + token;
    store.db.update(
        "insert into cooperation_invitation values(?,?,?,?,'PENDING',?,?)",
        invitation,
        id,
        revision,
        DealService.hash(token),
        java.sql.Timestamp.from(store.now().plus(Duration.ofDays(7))),
        store.time());
    store.db.update(
        "insert into cooperation_email_job(id,invitation_id,status,next_attempt_at,payload)"
            + " values(?,?,?,?,?)",
        job,
        invitation,
        email == null ? "MANUAL_REQUIRED" : "QUEUED",
        email == null ? null : store.time(),
        cipher.encrypt(email == null ? "" : email, link));
    if (owner != null)
      store.db.update(
          "insert into"
              + " notification(id,user_id,type,title,message,entity_type,entity_id,is_read,created_at)"
              + " values(?,?,'COOPERATION_INVITATION','Cooperation invitation','A Brand has sent a"
              + " cooperation proposal.','COOPERATION_DEAL',?,false,?)",
          UUID.randomUUID(),
          owner,
          id,
          store.time());
    if (email != null) events.publishEvent(new Queued(job));
    return Map.of(
        "invitationId",
        invitation,
        "dealId",
        id,
        "proposalRevision",
        revision,
        "emailStatus",
        email == null ? "MANUAL_REQUIRED" : "QUEUED",
        "portalNotificationCreated",
        owner != null);
  }

  Map<String, Object> owned(UUID invitation) {
    Permissions.require(Permission.MANAGE_OWN_RELATIONSHIP);
    return store.one(
        "select i.* from cooperation_invitation i join cooperation_deal d on d.id=i.deal_id where"
            + " i.id=? and d.brand_user_id=?",
        invitation,
        Permissions.actor().getId());
  }

  public boolean valid(Map<String, Object> i) {
    if (!"PENDING".equals(i.get("status")) || !instant(i.get("expires_at")).isAfter(store.now()))
      return false;
    var d = store.deal(uuid(i.get("deal_id")));
    if (!Set.of("CONTACTED", "NEGOTIATING").contains(d.get("status"))) return false;
    var p = store.latest(uuid(i.get("deal_id")));
    if (!p.get("revision").equals(i.get("proposal_revision"))) return false;
    for (var m : store.members(uuid(d.get("id"))))
      if (!Objects.equals(m.get("master_id"), d.get("master_id"))) return false;
    return true;
  }

  static Instant instant(Object value) {
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    if (value instanceof OffsetDateTime t) return t.toInstant();
    return (Instant) value;
  }

  @Transactional(readOnly = true)
  public Object preview(String token) {
    if (token == null || !token.matches("[a-f0-9]{64}"))
      throw new ValidationException("Invalid cooperation invitation");
    var i =
        store.one(
            "select * from cooperation_invitation where token_hash=?", DealService.hash(token));
    if (!valid(i)) throw conflict("Invitation is expired or replaced");
    var data =
        store.one(
            "select c.name,b.business_name from cooperation_deal d join campaign c on"
                + " c.id=d.campaign_id join brand_profile b on b.id=c.brand_profile_id where"
                + " d.id=?",
            i.get("deal_id"));
    return Map.of(
        "brandName",
        data.get("business_name"),
        "campaignName",
        data.get("name"),
        "message",
        "Sign in to view your cooperation proposal",
        "expiresAt",
        i.get("expires_at"));
  }

  @Transactional(readOnly = true)
  public Object manualLink(UUID invitation) {
    var i = owned(invitation);
    if (!valid(i)) throw conflict("Invitation is no longer valid");
    var j = store.one("select * from cooperation_email_job where invitation_id=?", invitation);
    if (!"MANUAL_REQUIRED".equals(j.get("status")) || j.get("payload") == null)
      throw conflict("No manual link available");
    return Map.of(
        "invitationId",
        invitation,
        "link",
        cipher.decrypt(j.get("payload").toString()).link(),
        "expiresAt",
        i.get("expires_at"));
  }

  @Transactional(readOnly = true)
  public Object status(UUID invitation) {
    var i = owned(invitation);
    var j = store.one("select * from cooperation_email_job where invitation_id=?", invitation);
    return statusView(i, j);
  }

  Object statusView(Map<String, Object> i, Map<String, Object> j) {
    var result = new LinkedHashMap<String, Object>();
    result.put("invitationId", i.get("id"));
    result.put("emailStatus", j.get("status"));
    result.put("attempts", j.get("attempts"));
    result.put("nextAttemptAt", j.get("next_attempt_at"));
    result.put("sentAt", j.get("sent_at"));
    result.put("canRetry", canRetry(i, j));
    return result;
  }

  List<Instant> retries(Map<String, Object> j) {
    var result = new ArrayList<Instant>();
    for (var t : store.decode(j.get("manual_retries"))) result.add(Instant.parse(t.asText()));
    return result;
  }

  boolean canRetry(Map<String, Object> i, Map<String, Object> j) {
    return "FAILED".equals(j.get("status"))
        && j.get("payload") != null
        && valid(i)
        && j.get("last_attempt_at") != null
        && !instant(j.get("last_attempt_at")).plusSeconds(60).isAfter(store.now())
        && retries(j).stream().filter(t -> t.isAfter(store.now().minusSeconds(3600))).count() < 5;
  }

  @Transactional
  public Object retry(UUID invitation) {
    var i = owned(invitation);
    store.one("select id from \"user\" where id=? for update", Permissions.actor().getId());
    var d = store.deal(uuid(i.get("deal_id")));
    store.one("select id from campaign where id=? for update", d.get("campaign_id"));
    i = store.one("select * from cooperation_invitation where id=? for update", invitation);
    var j =
        store.one(
            "select * from cooperation_email_job where invitation_id=? for update", invitation);
    if (!"FAILED".equals(j.get("status")) || !valid(i) || j.get("payload") == null)
      throw conflict("Invitation email cannot be retried");
    if (!canRetry(i, j))
      throw new BusinessException(
          ErrorCode.CLAIM_EMAIL_RATE_LIMITED, "Cooperation retry limit exceeded");
    sender.requireConfigured();
    cipher.requireConfigured();
    var times =
        retries(j).stream()
            .filter(t -> t.isAfter(store.now().minusSeconds(3600)))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    times.add(store.now());
    store.db.update(
        "update cooperation_email_job set"
            + " status='QUEUED',cycle_attempts=0,next_attempt_at=?,manual_retries=?::jsonb where"
            + " id=?",
        store.time(),
        store.encode(times),
        j.get("id"));
    events.publishEvent(new Queued(uuid(j.get("id"))));
    return Map.of("invitationId", invitation, "emailStatus", "QUEUED");
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public List<UUID> due() {
    return store.db.queryForList(
        "select j.id from cooperation_email_job j join cooperation_invitation i on"
            + " i.id=j.invitation_id where (j.status='QUEUED' and j.next_attempt_at<=?) or"
            + " (j.status='PROCESSING' and j.lease_until<=?) or (j.payload is not null and"
            + " i.expires_at<=?) order by j.id limit 100",
        UUID.class,
        store.time(),
        store.time(),
        store.time());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Work prepare(UUID id) {
    var initial =
        store.one(
            "select i.* from cooperation_invitation i join cooperation_email_job j on"
                + " j.invitation_id=i.id where j.id=?",
            id);
    var d = store.deal(uuid(initial.get("deal_id")));
    store.one("select id from campaign where id=? for update", d.get("campaign_id"));
    var i =
        store.one("select * from cooperation_invitation where id=? for update", initial.get("id"));
    var j = store.one("select * from cooperation_email_job where id=? for update", id);
    if (j.get("payload") == null) return null;
    if ("PROCESSING".equals(j.get("status")) && instant(j.get("lease_until")).isAfter(store.now()))
      return null;
    if (!valid(i)) {
      terminate(id, instant(i.get("expires_at")).isAfter(store.now()) ? "CANCELLED" : "EXPIRED");
      return null;
    }
    boolean due =
        "QUEUED".equals(j.get("status")) && !instant(j.get("next_attempt_at")).isAfter(store.now());
    boolean abandoned =
        "PROCESSING".equals(j.get("status")) && !instant(j.get("lease_until")).isAfter(store.now());
    if (!due && !abandoned) return null;
    if (((Number) j.get("cycle_attempts")).intValue() >= 5) {
      store.db.update(
          "update cooperation_email_job set"
              + " status='FAILED',processing_token=null,lease_until=null,next_attempt_at=null where"
              + " id=?",
          id);
      return null;
    }
    UUID processing = UUID.randomUUID();
    store.db.update(
        "update cooperation_email_job set"
            + " status='PROCESSING',attempts=attempts+1,cycle_attempts=cycle_attempts+1,last_attempt_at=?,next_attempt_at=null,lease_until=?,processing_token=?"
            + " where id=?",
        store.time(),
        java.sql.Timestamp.from(store.now().plusSeconds(120)),
        processing,
        id);
    return new Work(id, processing, j.get("payload").toString());
  }

  void terminate(UUID id, String status) {
    store.db.update(
        "update cooperation_email_job set"
            + " status=?,payload=null,next_attempt_at=null,lease_until=null,processing_token=null"
            + " where id=?",
        status,
        id);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(Work work, boolean success) {
    var j = store.one("select * from cooperation_email_job where id=? for update", work.id());
    if (!"PROCESSING".equals(j.get("status"))
        || !work.token().equals(j.get("processing_token"))
        || !instant(j.get("lease_until")).isAfter(store.now())) return;
    if (success) {
      store.db.update(
          "update cooperation_email_job set sent_at=? where id=?", store.time(), work.id());
      terminate(work.id(), "SENT");
    } else {
      int attempts = ((Number) j.get("cycle_attempts")).intValue();
      store.db.update(
          "update cooperation_email_job set"
              + " status=?,next_attempt_at=?,processing_token=null,lease_until=null where id=?",
          attempts >= 5 ? "FAILED" : "QUEUED",
          attempts >= 5
              ? null
              : java.sql.Timestamp.from(store.now().plusSeconds(15L << (attempts - 1))),
          work.id());
    }
  }
}
