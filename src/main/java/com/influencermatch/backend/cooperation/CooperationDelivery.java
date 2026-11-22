package com.influencermatch.backend.cooperation;

import static com.influencermatch.backend.cooperation.DealStore.*;

import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.email.ClaimEmailCipher;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.net.URI;
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

  public String plannedContactChannel(Map<String, Object> deal) {
    if (deal.get("master_id") != null) return "IN_APP_PORTAL";
    return publicContactEmail(deal) == null ? "MANUAL_SOCIAL_LINK" : "SYSTEM_EMAIL_OUTBOX";
  }

  public List<String> availableDeliveryMethods(Map<String, Object> deal) {
    if (deal.get("master_id") != null) {
      var user = store.optional("select u.email,u.role,u.status,u.email_verified from creator_master m join \"user\" u on u.id=m.user_id where m.id=?", deal.get("master_id"));
      if (user.isEmpty() || !"CREATOR".equals(user.get().get("role")) || !"ACTIVE".equals(user.get().get("status")) || !Boolean.TRUE.equals(user.get().get("email_verified"))) return List.of();
      var methods = new ArrayList<String>();
      if (user.get().get("email") != null && EMAIL.matcher(user.get().get("email").toString()).matches()) methods.add("PORTAL_EMAIL");
      methods.add("PORTAL_ONLY");
      if (methods.contains("PORTAL_EMAIL")) methods.add("EMAIL_ONLY");
      methods.add("MANUAL_LINK");
      return methods;
    }
    return publicContactEmail(deal) == null ? List.of("MANUAL_LINK") : List.of("EMAIL_ONLY", "MANUAL_LINK");
  }

  private String publicContactEmail(Map<String, Object> deal) {
    var members = store.members(uuid(deal.get("id")));
    if (members.isEmpty()) return null;
    var metric = store.optional(
        "select contact from public_creator_metric where creator_id=? order by collected_at desc,id desc limit 1",
        uuid(members.getFirst().get("creator_id")));
    if (metric.isEmpty() || metric.get().get("contact") == null) return null;
    var matcher = EMAIL.matcher(metric.get().get("contact").toString());
    return matcher.find() ? matcher.group() : null;
  }

  final DealStore store;
  final ClaimEmailCipher cipher;
  final VerificationEmailSender sender;
  final ApplicationEventPublisher events;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  com.influencermatch.backend.notification.service.NotificationService notificationService;

  @Value("${application.auth.creator-invitation.frontend-base-url:http://localhost:5173}")
  String frontend;

  private static final String INVITATION_PATH = "/cooperation-invitations";
  private static final String TOKEN_PARAMETER = "token=";
  private static final Pattern TOKEN = Pattern.compile("[a-f0-9]{64}");

  static final Pattern EMAIL =
      Pattern.compile(
          "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}");

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> enqueue(Map<String, Object> d, Map<String, Object> p) {
    return enqueue(d, p, null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> enqueue(Map<String, Object> d, Map<String, Object> p, String requestedMethod) {
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
      email = u.get("email") == null ? null : u.get("email").toString();
      if (email != null && !EMAIL.matcher(email).matches()) email = null;
    } else {
      email = publicContactEmail(d);
    }
    String method = requestedMethod == null ? owner != null ? "PORTAL_EMAIL" : email != null ? "EMAIL_ONLY" : "MANUAL_LINK" : requestedMethod;
    validateDeliveryMethod(method, owner != null, email != null);
    boolean sendEmail = Set.of("PORTAL_EMAIL", "EMAIL_ONLY").contains(method);
    boolean notifyPortal = Set.of("PORTAL_EMAIL", "PORTAL_ONLY").contains(method);
    String emailStatus = sendEmail ? "QUEUED" : "MANUAL_LINK".equals(method) ? "MANUAL_REQUIRED" : "NOT_REQUESTED";
    cipher.requireConfigured();
    if (sendEmail) sender.requireConfigured();
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    UUID invitation = UUID.randomUUID(), job = UUID.randomUUID();
    String link = invitationLink(token);
    store.db.update(
        "insert into cooperation_invitation values(?,?,?,?,'PENDING',?,?)",
        invitation,
        id,
        revision,
        DealService.hash(token),
        java.sql.Timestamp.from(store.now().plus(Duration.ofDays(7))),
        store.time());
    store.db.update(
        "insert into cooperation_email_job(id,invitation_id,status,next_attempt_at,payload,delivery_method)"
            + " values(?,?,?,?,?,?)",
        job,
        invitation,
        emailStatus,
        sendEmail ? store.time() : null,
        cipher.encrypt(sendEmail ? email : "", link), method);
    if (notifyPortal) {
      if (notificationService != null) {
        notificationService.createAndDispatch(
            owner,
            com.influencermatch.backend.notification.enums.NotificationType.COOPERATION_INVITATION,
            "Cooperation invitation",
            "A Brand has sent a cooperation proposal.",
            "COOPERATION_DEAL",
            id);
      } else {
        store.db.update(
            "insert into"
                + " notification(id,user_id,type,title,message,entity_type,entity_id,is_read,created_at)"
                + " values(?,?,'COOPERATION_INVITATION','Cooperation invitation','A Brand has sent a"
                + " cooperation proposal.','COOPERATION_DEAL',?,false,?)",
            UUID.randomUUID(),
            owner,
            id,
            store.time());
      }
    }
    if (sendEmail) events.publishEvent(new Queued(job));
    return Map.of(
        "invitationId",
        invitation,
        "dealId",
        id,
        "proposalRevision",
        revision,
        "emailStatus",
        emailStatus,
        "portalNotificationCreated",
        notifyPortal,
        "deliveryMethod", method);
  }

  static void validateDeliveryMethod(String method, boolean portal, boolean email) {
    if (!Set.of("PORTAL_EMAIL", "PORTAL_ONLY", "EMAIL_ONLY", "MANUAL_LINK").contains(method)
        || (Set.of("PORTAL_EMAIL", "PORTAL_ONLY").contains(method) && !portal)
        || (Set.of("PORTAL_EMAIL", "EMAIL_ONLY").contains(method) && !email))
      throw conflict("Selected delivery method is not available for this recipient");
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

  private String invitationLink(String token) {
    URI base = parseInvitationUri(frontend);
    String scheme = base.getScheme();
    if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
        || base.getHost() == null
        || base.getRawUserInfo() != null
        || base.getRawQuery() != null
        || base.getRawFragment() != null)
      throw new BusinessException(
          ErrorCode.EMAIL_DELIVERY_UNAVAILABLE, "Cooperation frontend URL is unavailable");
    return base.toString().replaceAll("/+$", "") + INVITATION_PATH + "?" + TOKEN_PARAMETER + token;
  }

  String currentInvitationLink(String storedLink) {
    URI stored = parseInvitationUri(storedLink);
    String query = stored.getRawQuery();
    if (stored.getRawPath() == null
        || !stored.getRawPath().endsWith(INVITATION_PATH)
        || stored.getRawFragment() != null
        || query == null
        || !query.startsWith(TOKEN_PARAMETER))
      throw invalidStoredLink();
    String token = query.substring(TOKEN_PARAMETER.length());
    if (!TOKEN.matcher(token).matches()) throw invalidStoredLink();
    return invitationLink(token);
  }

  private static URI parseInvitationUri(String value) {
    if (value == null) throw invalidStoredLink();
    try {
      return URI.create(value);
    } catch (IllegalArgumentException e) {
      // URI parsing errors may contain the invitation token.
      throw invalidStoredLink();
    }
  }

  private static BusinessException invalidStoredLink() {
    return new BusinessException(
        ErrorCode.EMAIL_DELIVERY_UNAVAILABLE, "Cooperation invitation URL is unavailable");
  }

  @Transactional(readOnly = true)
  public Object preview(String token) {
    if (token == null || !TOKEN.matcher(token).matches())
      throw new ValidationException("Invalid cooperation invitation");
    var i =
        store.one(
            "select * from cooperation_invitation where token_hash=?", DealService.hash(token));
    // An unowned channel may have been claimed since delivery. Minimal public preview
    // remains available; authenticated continuation separately verifies its current owner.
    var d = store.deal(uuid(i.get("deal_id")));
    var p = store.latest(uuid(i.get("deal_id")));
    if (!"PENDING".equals(i.get("status")) || !instant(i.get("expires_at")).isAfter(store.now())
        || !Set.of("CONTACTED", "NEGOTIATING").contains(d.get("status"))
        || !p.get("revision").equals(i.get("proposal_revision"))
        || d.get("master_id") != null && !valid(i))
      throw conflict("Invitation is expired or replaced");
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
        currentInvitationLink(cipher.decrypt(j.get("payload").toString()).link()),
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
    result.put("invitationStatus", i.get("status"));
    result.put("expiresAt", i.get("expires_at"));
    result.put("active", valid(i));
    result.put("emailStatus", j.get("status"));
    result.put("deliveryMethod", j.get("delivery_method"));
    if (j.get("delivery_method") != null) result.put("portalNotificationCreated", Set.of("PORTAL_EMAIL", "PORTAL_ONLY").contains(j.get("delivery_method")));
    result.put("attempts", j.get("attempts"));
    result.put("nextAttemptAt", j.get("next_attempt_at"));
    result.put("sentAt", j.get("sent_at"));
    result.put("canRetry", canRetry(i, j));
    result.put("canResend", canResend(i, j));
    if ("SENT".equals(j.get("status")) && j.get("payload") == null)
      result.put("resendUnavailableReason", "PAYLOAD_UNAVAILABLE");
    else if ("SENT".equals(j.get("status")) && valid(i) && !canResend(i, j)) {
      if (Objects.equals(store.deal(uuid(i.get("deal_id"))).get("accepted_revision"), i.get("proposal_revision")))
        result.put("resendUnavailableReason", "PROPOSAL_ACCEPTED");
      else if (j.get("last_attempt_at") == null)
        result.put("resendUnavailableReason", "STATE_UNAVAILABLE");
      else {
        Instant available = instant(j.get("last_attempt_at")).plusSeconds(60);
        var recent = retries(j).stream().filter(t -> t.isAfter(store.now().minusSeconds(3600))).sorted().toList();
        if (recent.size() >= 5) {
          var hourlyAvailable = recent.get(recent.size() - 5).plusSeconds(3600);
          if (hourlyAvailable.isAfter(available)) available = hourlyAvailable;
          result.put("resendUnavailableReason", "HOURLY_LIMIT");
        } else result.put("resendUnavailableReason", "COOLDOWN");
        result.put("resendAvailableAt", available.toString());
      }
    }
    return result;
  }

  List<Instant> retries(Map<String, Object> j) {
    var result = new ArrayList<Instant>();
    for (var t : store.decode(j.get("manual_retries"))) result.add(Instant.parse(t.asText()));
    return result;
  }

  boolean canRetry(Map<String, Object> i, Map<String, Object> j) {
    return "FAILED".equals(j.get("status"))
        && canRequestEmail(i, j);
  }

  boolean canResend(Map<String, Object> i, Map<String, Object> j) {
    return "SENT".equals(j.get("status")) && canRequestEmail(i, j);
  }

  private boolean canRequestEmail(Map<String, Object> i, Map<String, Object> j) {
    return j.get("payload") != null
        && valid(i)
        && !Objects.equals(store.deal(uuid(i.get("deal_id"))).get("accepted_revision"), i.get("proposal_revision"))
        && j.get("last_attempt_at") != null
        && !instant(j.get("last_attempt_at")).plusSeconds(60).isAfter(store.now())
        && retries(j).stream().filter(t -> t.isAfter(store.now().minusSeconds(3600))).count() < 5;
  }

  @Transactional
  public Object retry(UUID invitation) {
    return requeueEmail(invitation, "FAILED");
  }

  @Transactional
  public Object resend(UUID invitation) {
    return requeueEmail(invitation, "SENT");
  }

  private Object requeueEmail(UUID invitation, String expectedStatus) {
    var i = owned(invitation);
    store.one("select id from \"user\" where id=? for update", Permissions.actor().getId());
    var d = store.deal(uuid(i.get("deal_id")));
    store.one("select id from campaign where id=? for update", d.get("campaign_id"));
    i = store.one("select * from cooperation_invitation where id=? for update", invitation);
    var j =
        store.one(
            "select * from cooperation_email_job where invitation_id=? for update", invitation);
    if (!expectedStatus.equals(j.get("status")) || !valid(i) || j.get("payload") == null)
      throw conflict("Invitation email cannot be retried");
    if (!canRequestEmail(i, j))
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
      String status = "SENT".equals(j.get("status"))
          ? "SENT"
          : instant(i.get("expires_at")).isAfter(store.now()) ? "CANCELLED" : "EXPIRED";
      terminate(id, status);
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
          "update cooperation_email_job set status='SENT',sent_at=?,next_attempt_at=null,"
              + " lease_until=null,processing_token=null where id=?",
          store.time(),
          work.id());
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
