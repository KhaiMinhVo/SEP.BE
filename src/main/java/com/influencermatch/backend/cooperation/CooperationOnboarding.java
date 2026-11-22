package com.influencermatch.backend.cooperation;

import static com.influencermatch.backend.cooperation.DealStore.*;

import com.influencermatch.backend.auth.service.CreatorRegistrationInvitationService;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.creator.email.ClaimEmailCipher;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A bearer link is only navigation context. Ownership is always checked against current DB rows. */
@Service @RequiredArgsConstructor
public class CooperationOnboarding {
  final DealStore store;
  final ClaimEmailCipher cipher;
  final CreatorRegistrationInvitationService registration;
  final DealService deals;
  final com.influencermatch.backend.creator.portal.CreatorPortalProjection creatorProjection;
  final AuditService audit;

  Map<String, Object> invitation(String token) {
    if (token == null || !token.matches("[a-f0-9]{64}")) throw new ValidationException("Invalid invitation");
    var i = store.one("select * from cooperation_invitation where token_hash=?", DealService.hash(token));
    check(i);
    return i;
  }

  void check(Map<String, Object> i) {
    var d = store.deal(uuid(i.get("deal_id")));
    var c = store.one("select status from campaign where id=?", d.get("campaign_id"));
    if (!"PENDING".equals(i.get("status")) || !CooperationDelivery.instant(i.get("expires_at")).isAfter(store.now())
        || !Set.of("CONTACTED", "NEGOTIATING").contains(d.get("status"))
        || Set.of("COMPLETED", "ARCHIVED", "CLOSED").contains(c.get("status"))
        || !store.latest(uuid(d.get("id"))).get("revision").equals(i.get("proposal_revision")))
      throw conflict("Invitation expired, replaced or no longer open");
  }

  String recipient(Map<String, Object> i) {
    var job = store.one("select payload,delivery_method from cooperation_email_job where invitation_id=?", i.get("id"));
    if ("MANUAL_LINK".equals(job.get("delivery_method"))) return null;
    if ("PORTAL_ONLY".equals(job.get("delivery_method"))) {
      var d = store.deal(uuid(i.get("deal_id")));
      return store.one("select u.email from creator_master m join \"user\" u on u.id=m.user_id where m.id=?",
          d.get("master_id")).get("email").toString().toLowerCase(Locale.ROOT);
    }
    if (job.get("payload") == null) throw conflict("Recipient proof is no longer available; contact the Brand");
    var email = cipher.decrypt(job.get("payload").toString()).email();
    if (email == null || email.isBlank()) throw conflict("Recipient email is unavailable");
    return email.trim().toLowerCase(Locale.ROOT);
  }

  @Transactional
  public void requestRegistration(String token, String email) {
    var initial = invitation(token);
    // Serialize public requests and rate-limit by the original invitation, not only by email.
    var i = store.one("select * from cooperation_invitation where id=? for update", initial.get("id"));
    check(i);
    String expected = recipient(i);
    if (expected != null && !expected.equalsIgnoreCase(email)) return;
    var counts = store.one("select count(*) total,max(created_at) latest from audit_log where action='REQUEST_CREATOR_REGISTRATION'"
        + " and entity_id=? and created_at>?", i.get("id"), java.sql.Timestamp.from(store.now().minusSeconds(3600)));
    var latest = counts.get("latest");
    if (((Number) counts.get("total")).longValue() >= 5 || latest != null
        && CooperationDelivery.instant(latest).isAfter(store.now().minusSeconds(60)))
      throw new BusinessException(ErrorCode.EMAIL_OTP_RATE_LIMITED, "Please wait before requesting another registration email");
    audit.recordAs(null, "REQUEST_CREATOR_REGISTRATION", "CooperationInvitation", uuid(i.get("id")),
        null, Map.of("requested", true), "Requested Creator registration email; ownership unchanged");
    var d = store.deal(uuid(i.get("deal_id")));
    registration.inviteFromCooperation(email.toLowerCase(Locale.ROOT), uuid(d.get("brand_user_id")), token);
  }

  @Transactional(readOnly = true)
  public Object context(String token) {
    var i = invitation(token);
    Permissions.require(Permission.CLAIM_CREATOR_PROFILE);
    deals.fresh(Permission.CLAIM_CREATOR_PROFILE);
    var d = store.deal(uuid(i.get("deal_id")));
    var members = store.members(uuid(d.get("id")));
    boolean owns = ownsAll(members);
    String expected = owns ? null : recipient(i);
    if (!owns && expected != null && !expected.equalsIgnoreCase(Permissions.actor().getEmail()))
      return Map.of("status", "ACCOUNT_MISMATCH");
    if (d.get("master_id") != null && !owns) return Map.of("status", "OWNERSHIP_CONFLICT");
    var channels = members.stream().map(m -> Map.of("creatorId", m.get("creator_id"),
        "platform", m.get("platform"), "username", Objects.toString(m.get("user_name"), ""))).toList();
    return Map.of("status", owns ? "READY" : "NEEDS_CHANNEL_VERIFICATION", "channels", channels);
  }

  boolean ownsAll(List<Map<String, Object>> members) {
    if (members.isEmpty() || Permissions.actor().getRole() != com.influencermatch.backend.user.enums.Role.CREATOR) return false;
    var ids = members.stream().map(m -> uuid(m.get("creator_id"))).toList();
    var verified = creatorProjection.project(ids, false);
    if (ids.stream().anyMatch(id -> !verified.containsKey(id) || !verified.get(id).verification().verified())) return false;
    return members.stream().allMatch(m -> "AUTHORIZED".equals(m.get("source_type")) && m.get("master_id") != null
        && !store.db.queryForList("select m.id from creator_master m join \"user\" u on u.id=m.user_id"
            + " where m.id=? and m.user_id=? and m.status='ACTIVE' and u.status='ACTIVE' and u.role='CREATOR' and u.email_verified",
            m.get("master_id"), Permissions.actor().getId()).isEmpty());
  }

  @Transactional
  public Object proceed(String token) {
    Permissions.require(Permission.RESPOND_OWN_COOPERATION);
    deals.lockUser();
    deals.fresh(Permission.RESPOND_OWN_COOPERATION);
    var initial = invitation(token);
    var d = store.deal(uuid(initial.get("deal_id")));
    store.one("select id from campaign where id=? for update", d.get("campaign_id"));
    d = deals.lockMembersAndDeal(d);
    var i = store.one("select * from cooperation_invitation where id=? for update", initial.get("id"));
    check(i);
    var members = store.members(uuid(d.get("id")));
    if (!ownsAll(members)) throw new ForbiddenException("Verify control of every invited channel before continuing");
    UUID master = uuid(members.getFirst().get("master_id"));
    if (members.stream().anyMatch(m -> !master.equals(m.get("master_id")))) throw conflict("Channels must belong to one Master");
    if (d.get("master_id") == null) {
      if (!store.db.queryForList("select id from cooperation_deal where campaign_id=? and id<>? and status<>'CANCELLED' and recipient_key=?",
          d.get("campaign_id"), d.get("id"), "MASTER:" + master).isEmpty())
        throw conflict("The Brand already has another deal for these channels; ask the Brand to resolve it");
      store.db.update("update cooperation_deal set master_id=?,recipient_key=?,revision=revision+1,updated_at=? where id=?",
          master, "MASTER:" + master, store.time(), d.get("id"));
      audit.record("BIND_COOPERATION_RECIPIENT", "CooperationDeal", uuid(d.get("id")), null,
          Map.of("masterId", master), "Verified channel owner resumed an existing invitation; proposal not accepted");
    }
    var result = store.deal(uuid(d.get("id")));
    deals.portalAccess(result);
    return Map.of("dealId", result.get("id"));
  }
}
