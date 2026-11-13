package com.influencermatch.backend.cooperation;

import static com.influencermatch.backend.cooperation.DealStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.service.*;
import com.influencermatch.backend.creator.portal.CreatorPortalProjection;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DealService {
  final DealStore store;
  final CampaignWriteLock locks;
  final AuditService audit;
  final ExecutionSnapshot executionSnapshot;
  final CooperationDelivery delivery;
  final ExecutionHistoryReader historyReader;
  final CreatorPortalProjection creatorProjection;
  @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

  UUID actor() {
    return Permissions.actor().getId();
  }

  void brand() {
    Permissions.require(Permission.MANAGE_OWN_RELATIONSHIP);
    if (Permissions.actor().getRole() != com.influencermatch.backend.user.enums.Role.BRAND)
      throw new ForbiddenException("Brand required");
  }

  void lockUser() {
    store.one("select id from \"user\" where id=? for update", actor());
  }

  Campaign owned(UUID campaign) {
    brand();
    var c = locks.owned(campaign);
    fresh(Permission.MANAGE_OWN_RELATIONSHIP);
    return c;
  }

  public void fresh(Permission permission) {
    var u =
        store.one(
            "select role,status,email_verified,auth_version from \"user\" where id=?", actor());
    var role = com.influencermatch.backend.user.enums.Role.valueOf(u.get("role").toString());
    if (!"ACTIVE".equals(u.get("status"))
        || !Boolean.TRUE.equals(u.get("email_verified"))
        || ((Number) u.get("auth_version")).longValue() != Permissions.actor().getAuthVersion())
      throw new ForbiddenException("Account authorization changed");
    if (!RolePermissions.forRole(role).contains(permission)
        || store.db.queryForObject(
                "select count(*) from role_permission_override where role=? and permission=? and"
                    + " enabled=false",
                Long.class,
                role.name(),
                permission.name())
            > 0) throw new ForbiddenException("Permission was disabled");
  }

  Map<String, Object> readOwned(UUID campaign, UUID id) {
    brand();
    var d = store.deal(id);
    if (!campaign.equals(d.get("campaign_id")) || !actor().equals(d.get("brand_user_id")))
      throw new NotFoundException("Deal not found");
    return d;
  }

  Map<String, Object> locked(UUID campaign, UUID id) {
    var initial = readOwned(campaign, id);
    owned(campaign);
    return lockMembersAndDeal(initial);
  }

  Map<String, Object> lockMembersAndDeal(Map<String, Object> d) {
    UUID id = uuid(d.get("id"));
    for (var m : store.members(id))
      store.one("select id from creator where id=? for update", m.get("creator_id"));
    for (var m :
        store.db.queryForList(
            "select relationship_id from cooperation_member where deal_id=? order by"
                + " relationship_id",
            id))
      store.one("select id from relationship where id=? for update", m.get("relationship_id"));
    return store.one("select * from cooperation_deal where id=? for update", id);
  }

  void rev(Map<String, Object> d, long expected) {
    if (store.revision(d) != expected)
      throw new BusinessException(ErrorCode.DEAL_REVISION_CONFLICT, "Deal revision changed");
  }

  void mutable(Map<String, Object> d) {
    if (!Set.of("CONTACTED", "NEGOTIATING").contains(d.get("status")))
      throw conflict("Deal is no longer negotiable");
  }

  void currentOwnership(Map<String, Object> d) {
    var members = store.members(uuid(d.get("id")));
    UUID master = uuid(d.get("master_id"));
    if (members.isEmpty()) throw conflict("Deal has no channels");
    if (master != null
        && store
            .db
            .queryForList(
                "select m.id from creator_master m join \"user\" u on u.id=m.user_id where m.id=?"
                    + " and m.status='ACTIVE' and u.status='ACTIVE' and u.role='CREATOR' and"
                    + " u.email_verified",
                master)
            .isEmpty()) throw conflict("Master account is unavailable");
    for (var m : members)
      if (!Objects.equals(master, m.get("master_id")))
        throw conflict("Channel ownership changed; recreate the deal");
  }

  void record(UUID id, String action, long revision, Object metadata) {
    store.db.update(
        "insert into cooperation_history(id,deal_id,actor_id,action,revision,metadata,created_at)"
            + " values(?,?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        id,
        actor(),
        action,
        revision,
        store.encode(metadata),
        store.time());
    audit.record(
        action, "COOPERATION_DEAL", id, null, Map.of("revision", revision), "Cooperation workflow");
  }

  void stages(UUID id, String status) {
    store.db.update(
        "update relationship r set stage=?,updated_at=? where id in(select relationship_id from"
            + " cooperation_member where deal_id=?)",
        status,
        store.time(),
        id);
  }

  void invalidate(UUID id) {
    store.db.update(
        "update cooperation_invitation set status='REPLACED' where deal_id=? and status='PENDING'",
        id);
    store.db.update(
        "update cooperation_email_job j set"
            + " status='CANCELLED',payload=null,processing_token=null,lease_until=null,next_attempt_at=null"
            + " where invitation_id in(select id from cooperation_invitation where deal_id=?) and"
            + " status<>'SENT'",
        id);
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  JsonNode replay(String scope, String key, Object body) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
      throw new ValidationException("Idempotency-Key is required (1-100 safe characters)");
    var old =
        store.optional(
            "select * from cooperation_idempotency where actor_id=? and scope=? and key=?",
            actor(),
            scope,
            key);
    if (old.isEmpty()) return null;
    if (!hash(store.encode(body)).equals(old.get().get("request_hash")))
      throw new BusinessException(
          ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency key has different content");
    return store.decode(old.get().get("response"));
  }

  JsonNode remembered(String scope, String key, Object body, Object response) {
    store.db.update(
        "insert into cooperation_idempotency values(?,?,?,?,?::jsonb,?)",
        actor(),
        scope,
        key,
        hash(store.encode(body)),
        store.encode(response),
        store.time());
    return store.json.valueToTree(response);
  }

  @Transactional
  public JsonNode create(UUID campaign, CooperationDtos.Create request, String key) {
    owned(campaign);
    String scope = "create:" + campaign;
    var cached = replay(scope, key, request);
    if (cached != null) return cached;
    var ids = request.relationshipIds();
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 100
        || ids.contains(null)
        || new HashSet<>(ids).size() != ids.size())
      throw new ValidationException("Distinct relationshipIds required");
    var rows = new ArrayList<Map<String, Object>>();
    for (UUID id : ids) {
      var r =
          store.one(
              "select * from relationship where id=? and campaign_id=? and user_id=?",
              id,
              campaign,
              actor());
      rows.add(r);
    }
    var channels =
        rows.stream()
            .map(r -> uuid(r.get("creator_id")))
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    for (UUID c : channels) store.one("select id from creator where id=? for update", c);
    for (UUID id : ids.stream().sorted(Comparator.comparing(UUID::toString)).toList())
      store.one("select id from relationship where id=? for update", id);
    UUID master = null;
    boolean first = true;
    for (UUID c : channels) {
      var row = store.one("select master_id,source_type from creator where id=?", c);
      if (first) {
        master = uuid(row.get("master_id"));
        first = false;
      }
      if (!Objects.equals(master, row.get("master_id"))
          || (channels.size() > 1
              && (master == null || !"AUTHORIZED".equals(row.get("source_type")))))
        throw conflict("Grouped channels must belong to one verified Master");
    }
    String recipient = master == null ? "CHANNEL:" + channels.getFirst() : "MASTER:" + master;
    if (!store
        .db
        .queryForList(
            "select d.id from cooperation_deal d where d.campaign_id=? and d.status<>'CANCELLED'"
                + " and (d.recipient_key=? or (?::uuid is not null and exists(select 1 from"
                + " cooperation_member m join creator c on c.id=m.creator_id where m.deal_id=d.id"
                + " and c.master_id=?::uuid)))",
            campaign,
            recipient,
            master,
            master)
        .isEmpty()) throw conflict("Use the existing deal for this recipient");
    for (var r : rows) {
      if (!Set.of("SHORTLISTED", "CONTACTED", "NEGOTIATING", "CANCELLED").contains(r.get("stage")))
        throw conflict("Relationship cannot enter a new deal");
      if (!store
          .db
          .queryForList(
              "select deal_id from cooperation_member where relationship_id=? and active",
              r.get("id"))
          .isEmpty()) throw conflict("Relationship already belongs to a deal");
    }
    UUID id = UUID.randomUUID();
    store.db.update(
        "insert into"
            + " cooperation_deal(id,campaign_id,brand_user_id,master_id,recipient_key,status,created_at,updated_at)"
            + " values(?,?,?,?,?,'CONTACTED',?,?)",
        id,
        campaign,
        actor(),
        master,
        recipient,
        store.time(),
        store.time());
    for (var r : rows)
      store.db.update(
          "insert into cooperation_member(deal_id,relationship_id,creator_id) values(?,?,?)",
          id,
          r.get("id"),
          r.get("creator_id"));
    stages(id, "CONTACTED");
    record(id, "DEAL_CREATED", 1, Map.of("channelCount", channels.size()));
    return remembered(scope, key, request, view(store.deal(id)));
  }

  @Transactional(readOnly = true)
  public Object get(UUID campaign, UUID id) {
    return view(readOwned(campaign, id));
  }

  @Transactional(readOnly = true)
  public Object list(UUID campaign) {
    brand();
    store.one(
        "select c.id from campaign c join brand_profile b on b.id=c.brand_profile_id where c.id=?"
            + " and b.user_id=?",
        campaign,
        actor());
    return store
        .db
        .queryForList(
            "select * from cooperation_deal where campaign_id=? and brand_user_id=? order by"
                + " created_at desc limit 100",
            campaign,
            actor())
        .stream()
        .map(this::view)
        .toList();
  }

  Map<String, Object> view(Map<String, Object> d) {
    var v = new LinkedHashMap<String, Object>();
    for (String k :
        List.of(
            "id",
            "campaign_id",
            "master_id",
            "status",
            "revision",
            "source",
            "accepted_revision",
            "collaboration_id",
            "created_at",
            "updated_at"))
      v.put(camel(k), d.get(k) instanceof java.sql.Timestamp t ? t.toInstant() : d.get(k));
    v.put(
        "channels",
        store.members(uuid(d.get("id"))).stream()
            .map(
                m ->
                    Map.of(
                        "creatorId",
                        m.get("creator_id"),
                        "relationshipId",
                        m.get("relationship_id"),
                        "platform",
                        m.get("platform"),
                        "username",
                        m.get("user_name")))
            .toList());
    var p =
        store.optional(
            "select * from cooperation_proposal where deal_id=? order by revision desc limit 1",
            d.get("id"));
    v.put("proposal", p.map(this::proposalView).orElse(null));
    v.put(
        "offeredFee",
        store
            .optional(
                "select fee from cooperation_proposal where deal_id=? and kind='OFFER' order by"
                    + " revision desc limit 1",
                d.get("id"))
            .map(r -> r.get("fee"))
            .orElse(null));
    v.put(
        "counterofferFee",
        store
            .optional(
                "select fee from cooperation_proposal where deal_id=? and kind='COUNTEROFFER' order"
                    + " by revision desc limit 1",
                d.get("id"))
            .map(r -> r.get("fee"))
            .orElse(null));
    v.put(
        "agreedFee",
        d.get("collaboration_id") == null
            ? null
            : store
                .one("select agreed_fee from collaboration where id=?", d.get("collaboration_id"))
                .get("agreed_fee"));
    v.put("capacityAssessment", "NOT_EVALUATED");
    v.put("scheduleAssessment", "NOT_EVALUATED");
    v.put("exclusivityAssessment", "NOT_EVALUATED");
    if (d.get("collaboration_id") != null)
      v.put(
          "collaborationStatus",
          store
              .one("select status from collaboration where id=?", d.get("collaboration_id"))
              .get("status"));
    return v;
  }

  static String camel(String key) {
    StringBuilder s = new StringBuilder();
    boolean cap = false;
    for (char c : key.toCharArray()) {
      if (c == '_') {
        cap = true;
        continue;
      }
      s.append(cap ? Character.toUpperCase(c) : c);
      cap = false;
    }
    return s.toString();
  }

  Object proposalView(Map<String, Object> p) {
    return Map.of(
        "id",
        p.get("id"),
        "revision",
        p.get("revision"),
        "kind",
        p.get("kind"),
        "fee",
        p.get("fee"),
        "currency",
        p.get("currency"),
        "terms",
        p.get("terms"),
        "deliverables",
        store.decode(p.get("deliverables")),
        "createdAt",
        p.get("created_at"));
  }

  @Transactional(readOnly = true)
  public Object history(UUID campaign, UUID id) {
    readOwned(campaign, id);
    return historyRows(id);
  }

  Object historyRows(UUID id) {
    var events =
        historyReader.dealEvents(id).stream()
            .map(
                r -> {
                  var m = new LinkedHashMap<String, Object>();
                  r.forEach(
                      (k, v) -> {
                        if (!k.equals("id"))
                          m.put(camel(k), k.equals("metadata") ? store.decode(v) : v);
                      });
                  return m;
                })
            .toList();
    var proposals =
        store
            .db
            .queryForList(
                "select * from cooperation_proposal where deal_id=? order by revision", id)
            .stream()
            .map(this::proposalView)
            .toList();
    return Map.of("events", events, "proposals", proposals);
  }

  void validateProposal(UUID id, CooperationDtos.Proposal p) {
    if (p.fee() == null
        || p.fee().signum() <= 0
        || p.fee().scale() > 2
        || p.fee().precision() - p.fee().scale() > 17
        || !"VND".equals(p.currency())
        || p.terms() == null
        || p.terms().isBlank()
        || p.terms().length() > 4000
        || p.deliverables() == null
        || p.deliverables().isEmpty()
        || p.deliverables().size() > 100)
      throw new ValidationException("Positive VND fee, terms and deliverables required");
    var expected =
        new HashSet<>(store.members(id).stream().map(m -> uuid(m.get("creator_id"))).toList());
    var actual = new HashSet<UUID>();
    for (var a : p.deliverables()) {
      if (a == null
          || a.creatorId() == null
          || a.quantity() < 1
          || a.quantity() > 100
          || a.dueAt() == null
          || a.acceptanceChecklist() == null
          || a.acceptanceChecklist().isEmpty()
          || a.acceptanceChecklist().size() > 50
          || a.acceptanceChecklist().stream()
              .anyMatch(v -> v == null || v.isBlank() || v.length() > 500)
          || a.maxRevisionRounds() == null
          || a.maxRevisionRounds() < 0
          || a.maxRevisionRounds() > 10
          || a.contentType() == null
          || a.contentType().isBlank()
          || a.contentType().length() > 100
          || !expected.contains(a.creatorId()))
        throw new ValidationException("Invalid deliverable channel or content");
      actual.add(a.creatorId());
    }
    if (p.deliverables().stream().mapToLong(CooperationDtos.Deliverable::quantity).sum() > 1000
        || p.deliverables().stream()
            .anyMatch(
                a ->
                    new HashSet<>(a.acceptanceChecklist()).size()
                        != a.acceptanceChecklist().size()))
      throw new ValidationException(
          "At most 1000 execution units and distinct checklist entries are allowed");
    if (!expected.equals(actual))
      throw new ValidationException("Every channel needs a deliverable");
  }

  @Transactional
  public JsonNode propose(UUID campaign, UUID id, CooperationDtos.Proposal request, String key) {
    var d = locked(campaign, id);
    return proposeLocked(d, request, key, "OFFER");
  }

  JsonNode proposeLocked(
      Map<String, Object> d, CooperationDtos.Proposal request, String key, String kind) {
    UUID id = uuid(d.get("id"));
    String scope = "proposal:" + id;
    var cached = replay(scope, key, request);
    if (cached != null) return cached;
    rev(d, request.expectedRevision());
    mutable(d);
    currentOwnership(d);
    validateProposal(id, request);
    long revision = store.revision(d) + 1;
    invalidate(id);
    store.db.update(
        "insert into cooperation_proposal values(?,?,?,?,?,?,?,?::jsonb,?,?)",
        UUID.randomUUID(),
        id,
        revision,
        actor(),
        kind,
        request.fee(),
        request.currency(),
        store.encode(request.deliverables()),
        request.terms().trim(),
        store.time());
    store.db.update(
        "update cooperation_deal set"
            + " revision=?,status='NEGOTIATING',accepted_revision=null,accepted_by=null,accepted_at=null,updated_at=?"
            + " where id=?",
        revision,
        store.time(),
        id);
    stages(id, "NEGOTIATING");
    record(id, kind, revision, Map.of("proposalRevision", revision));
    return remembered(scope, key, request, view(store.deal(id)));
  }

  @Transactional
  public JsonNode send(UUID campaign, UUID id, CooperationDtos.Send request, String key) {
    var d = locked(campaign, id);
    String scope = "send:" + id;
    var cached = replay(scope, key, request);
    if (cached != null) return cached;
    rev(d, request.expectedRevision());
    mutable(d);
    currentOwnership(d);
    var proposal = store.latest(id);
    var result = delivery.enqueue(d, proposal);
    record(
        id,
        "INVITATION_CREATED",
        store.revision(d),
        Map.of("invitationId", result.get("invitationId")));
    return remembered(scope, key, request, result);
  }

  Map<String, Object> portalLocked(UUID id) {
    Permissions.require(Permission.RESPOND_OWN_COOPERATION);
    lockUser();
    fresh(Permission.RESPOND_OWN_COOPERATION);
    var d = store.deal(id);
    store.one("select id from campaign where id=? for update", d.get("campaign_id"));
    d = lockMembersAndDeal(d);
    portalAccess(d);
    return d;
  }

  void portalAccess(Map<String, Object> d) {
    Permissions.require(Permission.RESPOND_OWN_COOPERATION);
    var u = store.one("select role,status,email_verified from \"user\" where id=?", actor());
    if (!"CREATOR".equals(u.get("role"))
        || !"ACTIVE".equals(u.get("status"))
        || !Boolean.TRUE.equals(u.get("email_verified")))
      throw new ForbiddenException("Verified active Creator required");
    UUID master = uuid(d.get("master_id"));
    if (master == null
        || store
            .db
            .queryForList("select id from creator_master where id=? and user_id=?", master, actor())
            .isEmpty()) throw new NotFoundException("Deal not found");
    try {
      currentOwnership(d);
    } catch (BusinessException changed) {
      throw new NotFoundException("Deal not found");
    }
    for (var m : store.members(uuid(d.get("id"))))
      if (!"AUTHORIZED".equals(m.get("source_type"))) throw new NotFoundException("Deal not found");
  }

  @Transactional(readOnly = true)
  public Object portalList() {
    Permissions.require(Permission.RESPOND_OWN_COOPERATION);
    var result = new ArrayList<Object>();
    for (var d :
        store.db.queryForList(
            "select d.* from cooperation_deal d join creator_master m on m.id=d.master_id where"
                + " m.user_id=? order by d.updated_at desc limit 100",
            actor())) {
      try {
        portalAccess(d);
        result.add(view(d));
      } catch (NotFoundException | ForbiddenException ignored) {
      }
    }
    return result;
  }

  @Transactional(readOnly = true)
  public Object portalGet(UUID id) {
    var d = store.deal(id);
    portalAccess(d);
    return view(d);
  }

  @Transactional
  public Object respond(UUID id, String action, CooperationDtos.Revision request) {
    var d = portalLocked(id);
    rev(d, request.expectedRevision());
    mutable(d);
    var p = store.latest(id);
    if (action.equals("accept") && !"OFFER".equals(p.get("kind")))
      throw conflict("Brand must issue an offer before acceptance");
    if (action.equals("accept")) {
      if (Objects.equals(d.get("accepted_revision"), p.get("revision"))) return view(d);
      store.db.update(
          "update cooperation_deal set accepted_revision=?,accepted_by=?,accepted_at=?,updated_at=?"
              + " where id=?",
          p.get("revision"),
          actor(),
          store.time(),
          store.time(),
          id);
    } else {
      store.db.update(
          "update cooperation_deal set status='DECLINED',revision=revision+1,updated_at=? where"
              + " id=?",
          store.time(),
          id);
      invalidate(id);
      stages(id, "DECLINED");
    }
    record(
        id,
        action.equals("accept") ? "PROPOSAL_ACCEPTED" : "DEAL_DECLINED",
        store.revision(d),
        Map.of("proposalRevision", p.get("revision")));
    return view(store.deal(id));
  }

  @Transactional
  public JsonNode counter(UUID id, CooperationDtos.Proposal p, String key) {
    var d = portalLocked(id);
    if (!"OFFER".equals(store.latest(id).get("kind")))
      throw conflict("Wait for a Brand offer before countering");
    return proposeLocked(d, p, key, "COUNTEROFFER");
  }

  @Transactional
  public JsonNode confirm(UUID campaign, UUID id, CooperationDtos.Confirm request, String key) {
    Permissions.require(Permission.MANAGE_OWN_COLLABORATION);
    var d = locked(campaign, id);
    fresh(Permission.MANAGE_OWN_COLLABORATION);
    String scope = "confirm:" + id;
    var cached = replay(scope, key, request);
    if (cached != null) return cached;
    rev(d, request.expectedRevision());
    mutable(d);
    currentOwnership(d);
    var c = store.one("select * from campaign where id=?", campaign);
    if (!Set.of("READY_FOR_DISCOVERY", "ACTIVE").contains(c.get("status")))
      throw conflict("Campaign must be READY_FOR_DISCOVERY or ACTIVE");
    var p = store.latest(id);
    if ("PORTAL".equals(request.agreementSource())) {
      if (!Objects.equals(d.get("accepted_revision"), p.get("revision")))
        throw conflict("Current proposal has not been accepted");
      if (d.get("accepted_by") == null
          || store
              .db
              .queryForList(
                  "select u.id from creator_master m join \"user\" u on u.id=m.user_id where m.id=?"
                      + " and u.id=? and u.role='CREATOR' and u.status='ACTIVE' and"
                      + " u.email_verified",
                  d.get("master_id"),
                  d.get("accepted_by"))
              .isEmpty()) throw conflict("Acceptance owner is no longer valid");
    } else if ("EXTERNAL".equals(request.agreementSource())) {
      if (request.agreedAt() == null
          || request.agreedAt().isAfter(store.now())
          || blank(request.evidenceSource())
          || blank(request.evidenceDescription())
          || !https(request.evidenceUrl()))
        throw new ValidationException(
            "External agreement requires past agreement time, source and HTTPS evidence with"
                + " description");
    } else throw new ValidationException("Invalid agreement source");
    if (store.db.queryForObject(
            "select count(*) from cooperation_member m join relationship r on"
                + " r.id=m.relationship_id where m.deal_id=? and r.stage not in"
                + " ('CONTACTED','NEGOTIATING')",
            Long.class,
            id)
        > 0) throw conflict("Channel CRM state no longer permits confirmation");
    var budget = budgetRows(campaign);
    BigDecimal fee = (BigDecimal) p.get("fee");
    if (!"AVAILABLE".equals(budget.get("status"))
        || c.get("total_budget") == null
        || !"VND".equals(c.get("currency")))
      throw new BusinessException(
          ErrorCode.DEAL_BUDGET_UNAVAILABLE,
          "Set a VND budget and resolve legacy commitment data first");
    BigDecimal committed = (BigDecimal) budget.get("committedBudget");
    if (committed.add(fee).compareTo((BigDecimal) c.get("total_budget")) > 0)
      throw new BusinessException(
          ErrorCode.DEAL_BUDGET_EXCEEDED, "Confirmation would exceed Campaign budget");
    var recipients =
        new HashSet<>(
            store
                .db
                .queryForList(
                    "select distinct case when coalesce(c.master_id,cr.master_id) is null then"
                        + " 'CHANNEL:'||c.creator_id else"
                        + " 'MASTER:'||coalesce(c.master_id,cr.master_id) end recipient from"
                        + " collaboration c join creator cr on cr.id=c.creator_id where"
                        + " c.campaign_id=? and c.status<>'CANCELLED'",
                    campaign)
                .stream()
                .map(r -> r.get("recipient").toString())
                .toList());
    recipients.add(d.get("recipient_key").toString());
    if ("SINGLE".equals(c.get("creator_mode")) && recipients.size() > 1)
      throw new BusinessException(
          ErrorCode.DEAL_SINGLE_LIMIT, "Single Creator Campaign already has another Creator");
    var members = store.members(id);
    var primary = members.getFirst();
    UUID collaboration = UUID.randomUUID();
    store.db.update(
        "insert into"
            + " collaboration(id,relationship_id,campaign_id,creator_id,master_id,agreed_fee,currency,status,payment_status,note,updated_at)"
            + " values(?,?,?,?,?,?,?,'PLANNED','PENDING',?,?)",
        collaboration,
        primary.get("relationship_id"),
        campaign,
        primary.get("creator_id"),
        d.get("master_id"),
        fee,
        "VND",
        p.get("terms"),
        store.time());
    for (var m : members)
      store.db.update(
          "insert into collaboration_channel values(?,?,?)",
          collaboration,
          m.get("creator_id"),
          m.get("relationship_id"));
    executionSnapshot.create(
        collaboration, uuid(p.get("id")), store.decode(p.get("deliverables")), "PROPOSAL");
    store.db.update(
        "update cooperation_deal set"
            + " status='CONFIRMED',revision=revision+1,collaboration_id=?,updated_at=? where id=?",
        collaboration,
        store.time(),
        id);
    stages(id, "CONFIRMED");
    invalidate(id);
    store.db.update(
        "update relationship set agreed_fee=?,currency='VND' where id=?",
        fee,
        primary.get("relationship_id"));
    var proof = new LinkedHashMap<String, Object>();
    proof.put("agreementSource", request.agreementSource());
    proof.put("proposalRevision", p.get("revision"));
    if ("EXTERNAL".equals(request.agreementSource())) {
      proof.put("agreedAt", request.agreedAt());
      proof.put("evidenceSource", request.evidenceSource());
      proof.put("evidenceUrl", request.evidenceUrl());
      proof.put("evidenceDescription", request.evidenceDescription());
    }
    record(id, "DEAL_CONFIRMED", store.revision(d) + 1, proof);
    var response = view(store.deal(id));
    response.put(
        "warnings",
        c.get("target_creator_count") instanceof Number n && recipients.size() > n.intValue()
            ? List.of("TARGET_CREATOR_COUNT_EXCEEDED")
            : List.of());
    return remembered(scope, key, request, response);
  }

  static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  static boolean https(String s) {
    try {
      var u = URI.create(s);
      return "https".equals(u.getScheme()) && u.getHost() != null && u.getUserInfo() == null;
    } catch (Exception e) {
      return false;
    }
  }

  Map<String, Object> budgetRows(UUID campaign) {
    var c = store.one("select total_budget,currency from campaign where id=?", campaign);
    var rows =
        store.db.queryForList(
            "select agreed_fee,currency from collaboration where campaign_id=? and"
                + " status<>'CANCELLED'",
            campaign);
    boolean available =
        rows.stream()
            .allMatch(
                r ->
                    r.get("agreed_fee") instanceof BigDecimal b
                        && b.signum() >= 0
                        && "VND".equals(r.get("currency")));
    BigDecimal committed =
        available
            ? rows.stream()
                .map(r -> (BigDecimal) r.get("agreed_fee"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    var out = new LinkedHashMap<String, Object>();
    out.put("status", available ? "AVAILABLE" : "UNAVAILABLE");
    out.put("totalBudget", c.get("total_budget"));
    out.put("currency", c.get("currency"));
    out.put("committedBudget", committed);
    out.put(
        "remainingBudget",
        available && c.get("total_budget") instanceof BigDecimal b ? b.subtract(committed) : null);
    out.put(
        "confirmedCreatorCount",
        store.db.queryForObject(
            "select count(distinct"
                + " coalesce(c.master_id::text,cr.master_id::text,c.creator_id::text)) from"
                + " collaboration c join creator cr on cr.id=c.creator_id where c.campaign_id=? and"
                + " c.status<>'CANCELLED'",
            Long.class,
            campaign));
    out.put(
        "negotiatingBudget",
        store.db.queryForObject(
            "select coalesce(sum(p.fee),0) from cooperation_deal d join lateral (select fee from"
                + " cooperation_proposal where deal_id=d.id order by revision desc limit 1) p on"
                + " true where d.campaign_id=? and d.status='NEGOTIATING'",
            BigDecimal.class,
            campaign));
    return out;
  }

  @Transactional(readOnly = true)
  public Object budget(UUID campaign) {
    brand();
    store.one(
        "select c.id from campaign c join brand_profile b on b.id=c.brand_profile_id where c.id=?"
            + " and b.user_id=?",
        campaign,
        actor());
    return budgetRows(campaign);
  }

  @Transactional
  public Object setBudget(UUID campaign, CooperationDtos.Budget request, String match) {
    Permissions.require(Permission.UPDATE_CAMPAIGN);
    var c = locks.owned(campaign);
    fresh(Permission.UPDATE_CAMPAIGN);
    CampaignRevision.require(match, c.getId(), c.getCampaignRevision());
    if (c.getTotalBudget() != null
        || !Set.of("READY_FOR_DISCOVERY", "ACTIVE").contains(c.getStatus().name()))
      throw conflict("Only legacy campaigns without a budget may initialize it");
    var b = budgetRows(campaign);
    if (!"AVAILABLE".equals(b.get("status")))
      throw new BusinessException(
          ErrorCode.DEAL_BUDGET_UNAVAILABLE, "Resolve legacy commitment data first");
    if (request.totalBudget().compareTo((BigDecimal) b.get("committedBudget")) < 0)
      throw new BusinessException(
          ErrorCode.DEAL_BUDGET_EXCEEDED, "Budget is below existing commitments");
    c.setTotalBudget(request.totalBudget());
    c.setCurrency("VND");
    c.setCampaignRevision(c.getCampaignRevision() + 1);
    audit.record(
        "LEGACY_BUDGET_INITIALIZED",
        "CAMPAIGN",
        campaign,
        null,
        Map.of("totalBudget", request.totalBudget()),
        "Legacy VND budget initialized");
    em.flush();
    return Map.of(
        "budget",
        budgetRows(campaign),
        "campaignRevision",
        c.getCampaignRevision(),
        "etag",
        CampaignRevision.tag(campaign, c.getCampaignRevision()));
  }

  @Transactional
  public Object transition(UUID campaign, UUID id, String action, CooperationDtos.Cancel request) {
    Permissions.require(Permission.MANAGE_OWN_COLLABORATION);
    store.one("select id from rbac_guard where id=1 for update");
    var d = locked(campaign, id);
    fresh(Permission.MANAGE_OWN_COLLABORATION);
    rev(d, request.expectedRevision());
    if ("start".equals(action)) {
      if (!"CONFIRMED".equals(d.get("status"))) throw conflict("Confirmed deal required");
      if (!"ACTIVE"
          .equals(store.one("select status from campaign where id=?", campaign).get("status")))
        throw conflict("Campaign must be ACTIVE");
      var c =
          store.one(
              "select status from collaboration where id=? for update", d.get("collaboration_id"));
      if (!"PLANNED".equals(c.get("status")))
        throw conflict("Only PLANNED collaboration may start");
      if (!"READY".equals(assignmentStatus(uuid(d.get("collaboration_id")), d)))
        throw conflict("An eligible Creator must confirm the assignment before start");
      store.db.update(
          "update collaboration set status='IN_PROGRESS',start_at=?,updated_at=? where id=?",
          store.time(),
          store.time(),
          d.get("collaboration_id"));
    } else {
      if (Set.of("CANCELLED", "DECLINED").contains(d.get("status")))
        throw conflict("Deal is already closed");
      if (d.get("collaboration_id") != null) {
        var c =
            store.one(
                "select status from collaboration where id=? for update",
                d.get("collaboration_id"));
        if (!"PLANNED".equals(c.get("status")))
          throw conflict("Only PLANNED collaborations may be cancelled");
        store.db.update(
            "update collaboration set status='CANCELLED',updated_at=? where id=?",
            store.time(),
            d.get("collaboration_id"));
      }
      store.db.update("update cooperation_deal set status='CANCELLED' where id=?", id);
      store.db.update("update cooperation_member set active=false where deal_id=?", id);
      invalidate(id);
      stages(id, "CANCELLED");
    }
    store.db.update(
        "update cooperation_deal set revision=revision+1,updated_at=? where id=?",
        store.time(),
        id);
    record(
        id,
        "DEAL_" + action.toUpperCase(Locale.ROOT),
        store.revision(d) + 1,
        Map.of("reason", request.reason()));
    return view(store.deal(id));
  }

  @Transactional(readOnly = true)
  public Object notifications() {
    Permissions.require(Permission.VIEW_OWN_NOTIFICATION);
    return store.db.queryForList(
        "select id,type,title,message,entity_type,entity_id,is_read,created_at from notification"
            + " where user_id=? and type='COOPERATION_INVITATION' order by created_at desc limit"
            + " 100",
        actor());
  }

  @Transactional
  public Object readNotification(UUID id) {
    Permissions.require(Permission.MANAGE_OWN_NOTIFICATION);
    store.one("select id from notification where id=? and user_id=?", id, actor());
    store.db.update(
        "update notification set is_read=true,read_at=coalesce(read_at,?) where id=? and user_id=?",
        store.time(),
        id,
        actor());
    return Map.of("id", id, "isRead", true);
  }

  @Transactional
  public JsonNode confirmLegacy(
      UUID campaign,
      com.influencermatch.backend.collaboration.dto.CreateCollaborationRequest request,
      String key) {
    if (request.dealId() == null || request.expectedRevision() == null)
      throw new ValidationException(
          "Create a Deal/proposal and provide dealId, expectedRevision and agreement evidence");
    locked(campaign, request.dealId());
    fresh(Permission.MANAGE_OWN_COLLABORATION);
    String scope = "legacy-confirm:" + request.dealId();
    var cached = replay(scope, key, request);
    if (cached != null) return cached;
    store.one(
        "select id from relationship where id=? and campaign_id=?",
        request.relationshipId(),
        campaign);
    if (store.members(request.dealId()).stream()
        .noneMatch(m -> request.relationshipId().equals(m.get("relationship_id"))))
      throw new ValidationException("Relationship does not belong to the Deal");
    var p = store.latest(request.dealId());
    if (request.agreedFee() != null
        && request.agreedFee().compareTo((BigDecimal) p.get("fee")) != 0)
      throw conflict("Fee must match the current proposal");
    if (request.currency() != null && !request.currency().equals(p.get("currency")))
      throw conflict("Currency must match the proposal");
    var result =
        confirm(
            campaign,
            request.dealId(),
            new CooperationDtos.Confirm(
                request.expectedRevision(),
                request.agreementSource(),
                request.agreedAt(),
                request.evidenceSource(),
                request.evidenceUrl(),
                request.evidenceDescription()),
            key);
    return remembered(scope, key, request, result);
  }

  @Transactional(readOnly = true)
  public boolean canAccessCollaboration(UUID user, UUID collaboration) {
    var original =
        store.one("select master_id from collaboration where id=?", collaboration).get("master_id");
    var recipient = executionRecipient(collaboration);
    return recipient.isPresent() && user.equals(recipient.get().userId())
        && recipient.get().masterId().equals(original);
  }

  record ExecutionRecipient(UUID masterId, UUID userId) {}

  Optional<ExecutionRecipient> executionRecipient(UUID collaboration) {
    var rows =
        store.db.queryForList(
            "select cr.id,cr.master_id,cr.source_type,m.user_id,m.status master_status,u.role,u.status,u.email_verified from"
                + " collaboration c join collaboration_channel cc on cc.collaboration_id=c.id join"
                + " creator cr on cr.id=cc.creator_id left join creator_master m on"
                + " m.id=cr.master_id left join \"user\" u on u.id=m.user_id where c.id=?",
            collaboration);
    if (rows.isEmpty()) return Optional.empty();
    var master = uuid(rows.getFirst().get("master_id"));
    var user = uuid(rows.getFirst().get("user_id"));
    if (master == null || user == null
        || rows.stream().anyMatch(r -> !master.equals(r.get("master_id"))
            || !user.equals(r.get("user_id")) || !"AUTHORIZED".equals(r.get("source_type"))
            || !"ACTIVE".equals(r.get("master_status")) || !"CREATOR".equals(r.get("role"))
            || !"ACTIVE".equals(r.get("status")) || !Boolean.TRUE.equals(r.get("email_verified")))
        || Boolean.TRUE.equals(store.db.queryForObject(
            "select exists(select 1 from role_permission_override where role='CREATOR'"
                + " and permission='RESPOND_OWN_COOPERATION' and enabled=false)", Boolean.class)))
      return Optional.empty();
    var ids = rows.stream().map(row -> uuid(row.get("id"))).toList();
    var verification = creatorProjection.project(ids, false);
    if (ids.stream().anyMatch(id -> !verification.containsKey(id)
        || !verification.get(id).verification().verified()
        || !master.equals(verification.get(id).masterId()))) return Optional.empty();
    return Optional.of(new ExecutionRecipient(master, user));
  }

  Optional<Map<String, Object>> confirmedProposal(UUID deal) {
    return store.optional(
        "select p.* from cooperation_history h join cooperation_proposal p on p.deal_id=h.deal_id"
            + " and to_jsonb(p.revision)=h.metadata->'proposalRevision'"
            + " where h.deal_id=? and h.action='DEAL_CONFIRMED' order by h.revision desc,h.id desc limit 1",
        deal);
  }

  String assignmentStatus(UUID collaboration, Map<String, Object> deal) {
    var recipient = executionRecipient(collaboration);
    var master = uuid(deal.get("master_id"));
    var assigned = store.one("select master_id from collaboration where id=?", collaboration).get("master_id");
    if (master == null && confirmedProposal(uuid(deal.get("id"))).isEmpty())
      return "NO_CONFIRMED_PROPOSAL";
    if (recipient.isEmpty() || !Objects.equals(master, assigned)
        || (master != null && !master.equals(recipient.get().masterId())))
      return "WAITING_FOR_VERIFICATION";
    return master == null ? "WAITING_FOR_ACCEPTANCE" : "READY";
  }

  @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public List<Map<String, Object>> pendingAssignments() {
    requireAssignmentCreator();
    return store.db.queryForList(
            "select d.*,c.id collaboration_id,ca.name campaign_name,b.business_name brand_name"
                + " from collaboration c join cooperation_deal d on d.collaboration_id=c.id"
                + " join campaign ca on ca.id=c.campaign_id join brand_profile b on b.id=ca.brand_profile_id"
                + " where c.status='PLANNED' and c.master_id is null and d.status='CONFIRMED'"
                + " and d.master_id is null and exists(select 1 from collaboration_channel cc"
                + " join creator cr on cr.id=cc.creator_id join creator_master m on m.id=cr.master_id"
                + " where cc.collaboration_id=c.id and m.user_id=?) order by d.updated_at desc,d.id",
            actor()).stream()
        .flatMap(d -> {
          UUID collaboration = uuid(d.get("collaboration_id"));
          if (executionRecipient(collaboration).filter(r -> actor().equals(r.userId())).isEmpty())
            return java.util.stream.Stream.empty();
          return confirmedProposal(uuid(d.get("id"))).stream().map(p -> Map.<String, Object>of(
              "collaborationId", collaboration, "dealRevision", store.revision(d),
              "proposalRevision", store.revision(p), "campaignName", d.get("campaign_name"),
              "brandName", d.get("brand_name"), "channels", channels(collaboration).stream()
                  .map(ch -> Map.of("creatorId", ch.creatorId(), "platform", ch.platform(),
                      "username", Objects.toString(ch.username(), ""))).toList(),
              "deliverables", store.decode(p.get("deliverables")), "fee", p.get("fee"),
              "currency", p.get("currency"), "terms", p.get("terms")));
        }).toList();
  }

  void requireAssignmentCreator() {
    if (!"CREATOR".equals(Permissions.actor().getRole().name()))
      throw new ForbiddenException("Creator required");
    Permissions.require(Permission.RESPOND_OWN_COOPERATION);
    fresh(Permission.RESPOND_OWN_COOPERATION);
  }

  @Transactional
  public JsonNode acceptAssignment(UUID collaboration, ExecutionDtos.Assignment request, String key) {
    store.one("select id from rbac_guard where id=1 for update");
    lockUser();
    requireAssignmentCreator();
    var initial = store.one("select campaign_id from collaboration where id=?", collaboration);
    var campaign = uuid(initial.get("campaign_id"));
    store.one("select id from campaign where id=? for update", campaign);
    var d = lockMembersAndDeal(store.one(
        "select * from cooperation_deal where collaboration_id=?", collaboration));
    var row = store.one("select * from collaboration where id=? for update", collaboration);
    var recipient = executionRecipient(collaboration).filter(r -> actor().equals(r.userId()))
        .orElseThrow(() -> new ForbiddenException("Verified ownership of every channel is required"));
    if (row.get("master_id") != null && !recipient.masterId().equals(row.get("master_id")))
      throw new ForbiddenException("Collaboration is assigned to another Creator");
    String scope = "collaboration-assignment:" + collaboration;
    var old = replay(scope, key, request);
    if (old != null) return old;
    if (!"PLANNED".equals(row.get("status")) || !"CONFIRMED".equals(d.get("status"))
        || row.get("master_id") != null || d.get("master_id") != null)
      throw conflict("Collaboration is not awaiting Creator assignment");
    rev(d, request.expectedRevision());
    var proposal = confirmedProposal(uuid(d.get("id")))
        .orElseThrow(() -> conflict("Confirmed proposal is missing"));
    if (store.revision(proposal) != request.proposalRevision())
      throw conflict("Confirmed proposal revision changed");
    var master = recipient.masterId();
    var deal = uuid(d.get("id"));
    if (!store.members(deal).stream().map(m -> uuid(m.get("creator_id"))).toList().equals(
        store.db.queryForList("select creator_id from collaboration_channel where collaboration_id=? order by creator_id",
            UUID.class, collaboration)))
      throw conflict("The assigned channels differ from the confirmed deal");
    if (Boolean.TRUE.equals(store.db.queryForObject(
        "select exists(select 1 from cooperation_deal other where other.campaign_id=?"
            + " and other.id<>? and other.status<>'CANCELLED' and (other.master_id=? or exists("
            + " select 1 from cooperation_member m join creator ch on ch.id=m.creator_id"
            + " where m.deal_id=other.id and ch.master_id=?)))",
        Boolean.class, campaign, deal, master, master)))
      throw conflict("Creator already has a deal in this campaign");
    store.db.update(
        "update cooperation_deal set master_id=?,recipient_key=?,accepted_revision=?,accepted_by=?,"
            + "accepted_at=?,updated_at=?,revision=revision+1 where id=?",
        master, "MASTER:" + master, request.proposalRevision(), actor(), store.time(), store.time(), deal);
    store.db.update(
        "update collaboration set master_id=?,execution_revision=execution_revision+1,updated_at=? where id=?",
        master, store.time(), collaboration);
    long next = store.revision(d) + 1;
    record(deal, "CREATOR_ASSIGNMENT_ACCEPTED", next,
        Map.of("collaborationId", collaboration, "proposalRevision", request.proposalRevision(),
            "masterId", master, "creatorUserId", actor()));
    return remembered(scope, key, request, Map.of("collaborationId", collaboration,
        "dealRevision", next, "proposalRevision", request.proposalRevision(), "status", "PLANNED"));
  }

  @Transactional(readOnly = true)
  public java.util.List<com.influencermatch.backend.collaboration.dto.CollaborationChannelDto>
      channels(UUID collaboration) {
    return store.db.query(
        "select cc.creator_id,cc.relationship_id,c.master_id,c.platform,c.user_name from"
            + " collaboration_channel cc join creator c on c.id=cc.creator_id where"
            + " cc.collaboration_id=? order by cc.creator_id",
        (rs, n) ->
            new com.influencermatch.backend.collaboration.dto.CollaborationChannelDto(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getString(5)),
        collaboration);
  }

  @Transactional(readOnly = true)
  public Object portalHistory(UUID id) {
    var d = store.deal(id);
    portalAccess(d);
    return historyRows(id);
  }
}
