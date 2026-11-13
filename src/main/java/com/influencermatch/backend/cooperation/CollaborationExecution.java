package com.influencermatch.backend.cooperation;

import static com.influencermatch.backend.cooperation.DealStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.enums.Role;
import java.net.URI;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CollaborationExecution {
  final DealStore s;
  final DealService deals;
  final ExecutionSnapshot snapshots;
  final AuditService audit;
  final ExecutionHistoryReader history;

  UUID actor() {
    return Permissions.actor().getId();
  }

  boolean isBrand(Map<String, Object> c) {
    return Permissions.actor().getRole() == Role.BRAND && actor().equals(c.get("brand_user_id"));
  }

  Map<String, Object> initial(UUID id) {
    return s.one(
        "select c.*,b.user_id brand_user_id,ca.status campaign_status from collaboration c join"
            + " campaign ca on ca.id=c.campaign_id join brand_profile b on b.id=ca.brand_profile_id"
            + " where c.id=?",
        id);
  }

  void access(Map<String, Object> c, boolean brandOnly, Permission permission) {
    if (isBrand(c)) {
      Permissions.require(permission);
      deals.fresh(permission);
    } else {
      if (brandOnly || Permissions.actor().getRole() != Role.CREATOR)
        throw new ForbiddenException("Collaboration role or ownership required");
      Permissions.require(Permission.RESPOND_OWN_COOPERATION);
      deals.fresh(Permission.RESPOND_OWN_COOPERATION);
      if (!deals.canAccessCollaboration(actor(), uuid(c.get("id"))))
        throw new ForbiddenException("Current ownership of every channel required");
    }
  }

  public Map<String, Object> lock(UUID id, boolean brandOnly, Permission permission) {
    s.one("select id from rbac_guard where id=1 for update");
    var first = initial(id);
    s.one("select id from \"user\" where id=? for update", actor());
    s.one("select id from campaign where id=? for update", first.get("campaign_id"));
    for (var r :
        s.db.queryForList(
            "select creator_id from collaboration_channel where collaboration_id=? order by"
                + " creator_id",
            id)) s.one("select id from creator where id=? for update", r.get("creator_id"));
    for (var r :
        s.db.queryForList(
            "select relationship_id from collaboration_channel where collaboration_id=? order by"
                + " relationship_id",
            id))
      s.one("select id from relationship where id=? for update", r.get("relationship_id"));
    s.optional("select id from cooperation_deal where collaboration_id=? for update", id);
    s.one("select id from collaboration where id=? for update", id);
    var c = initial(id);
    access(c, brandOnly, permission);
    return c;
  }

  public void progress(Map<String, Object> c) {
    if (!"IN_PROGRESS".equals(c.get("status")) || !"ACTIVE".equals(c.get("campaign_status")))
      throw conflict("An IN_PROGRESS collaboration in an ACTIVE campaign is required");
  }

  void revision(Map<String, Object> row, String key, long expected) {
    if (((Number) row.get(key)).longValue() != expected)
      throw conflict("Execution revision changed");
  }

  void changed(UUID id, String action) {
    changed(id, action, Map.of());
  }

  void changed(UUID id, String action, Map<String, Object> details) {
    s.db.update(
        "update collaboration set execution_revision=execution_revision+1,updated_at=? where id=?",
        s.time(),
        id);
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("actorId", actor());
    metadata.putAll(details);
    audit.record(action, "COLLABORATION", id, null, metadata, null);
  }

  Map<String, Object> deliverable(UUID id, UUID d) {
    return s.one(
        "select * from collaboration_deliverable where id=? and collaboration_id=? for update",
        d,
        id);
  }

  ObjectNode view(UUID id) {
    var snapshot = s.json.<ObjectNode>valueToTree(history.snapshot(id));
    if (Permissions.actor().getRole() == Role.BRAND && "PLANNED".equals(snapshot.path("status").asText())
        && Permissions.has(Permission.MANAGE_OWN_RELATIONSHIP)) {
      s.optional("select * from cooperation_deal where collaboration_id=? and status='CONFIRMED'", id)
          .ifPresent(deal -> {
            snapshot.put("dealId", uuid(deal.get("id")).toString());
            snapshot.put("dealRevision", s.revision(deal));
            snapshot.put("assignmentStatus", deals.assignmentStatus(id, deal));
          });
    }
    return forActor(snapshot);
  }

  private ObjectNode forActor(JsonNode snapshot) {
    if (Permissions.actor().getRole() == Role.BRAND) return (ObjectNode) snapshot;
    var result = select(snapshot, "id", "campaignId", "campaignStatus", "status", "agreedFee",
        "currency", "executionSource", "executionRevision");
    var deliverables = result.putArray("deliverables");
    snapshot.path("deliverables").forEach(unit -> {
      var row = select(unit, "id", "creatorId", "contentType", "dueAt", "acceptanceChecklist",
          "maxRevisionRounds", "revisionRounds", "revision", "submissionRevision", "status");
      var submissions = row.putArray("submissions");
      unit.path("submissions").forEach(draft -> submissions.add(select(draft, "id", "revision",
          "url", "source", "createdAt")));
      var reviews = row.putArray("reviews");
      unit.path("reviews").forEach(review -> reviews.add(select(review, "id", "submissionRevision",
          "decision", "checklist", "reason", "createdAt")));
      var publications = row.putArray("publications");
      unit.path("publications").forEach(publication -> publications.add(select(publication, "id",
          "submissionRevision", "url", "earlyPublication", "exceptionAcknowledged")));
      deliverables.add(row);
    });
    var acceptances = result.putArray("acceptances");
    snapshot.path("acceptances").forEach(a -> acceptances.add(
        select(a, "id", "outcomeRevision", "acceptedAt", "reopenedAt")));
    var outcomes = result.putArray("outcomeRevisions");
    snapshot.path("outcomeRevisions").forEach(outcome -> {
      var row = select(outcome, "id", "revision", "source", "createdAt");
      var posts = row.putObject("payload").putArray("posts");
      outcome.path("payload").path("posts").forEach(post -> posts.add(select(post,
          "deliverableId", "submissionRevision", "periodStart", "periodEnd", "views", "clicks",
          "likes", "comments", "shares", "unavailableMetrics", "evidenceUrl")));
      row.set("calculation", select(outcome.path("calculation"), "metricSource", "views", "clicks",
          "likes", "comments", "shares"));
      outcomes.add(row);
    });
    return result;
  }

  private ObjectNode select(JsonNode source, String... fields) {
    var result = s.json.createObjectNode();
    for (var field : fields) if (source.has(field)) result.set(field, source.get(field));
    return result;
  }

  private JsonNode replay(String scope, String key, Object request) {
    var receipt = deals.replay(scope, key, request);
    return receipt == null ? null : forActor(receipt);
  }
  List<Map<String,Object>> rows(UUID id){return history.units(id);}

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public JsonNode read(UUID id) {
    var c = initial(id);
    access(c, false, Permission.MANAGE_OWN_COLLABORATION);
    return view(id);
  }

  public static String https(String value) {
    try {
      URI u = URI.create(value.trim());
      if (!"https".equalsIgnoreCase(u.getScheme())
          || u.getHost() == null
          || u.getUserInfo() != null
          || value.length() > 2000) throw new IllegalArgumentException();
      return new URI(
              "https",
              null,
              u.getHost().toLowerCase(Locale.ROOT),
              u.getPort(),
              u.getPath(),
              u.getQuery(),
              null)
          .toASCIIString();
    } catch (Exception e) {
      throw new ValidationException("Valid HTTPS URL required");
    }
  }

  public static String postKey(String platform, String url, String postId) {
    URI u = URI.create(https(url));
    String host = u.getHost().toLowerCase(Locale.ROOT);
    String canonical = null;
    if ("TIKTOK".equals(platform) && (host.equals("tiktok.com") || host.endsWith(".tiktok.com"))) {
      var m = java.util.regex.Pattern.compile("/video/([0-9]+)(?:/|$)").matcher(u.getPath());
      if (m.find()) canonical = m.group(1);
    } else if ("INSTAGRAM".equals(platform)
        && (host.equals("instagram.com") || host.endsWith(".instagram.com"))) {
      var m =
          java.util.regex.Pattern.compile("/(?:p|reel|tv)/([A-Za-z0-9_-]+)(?:/|$)")
              .matcher(u.getPath());
      if (m.find()) canonical = m.group(1);
    } else if ("FACEBOOK".equals(platform)
        && (host.equals("facebook.com") || host.endsWith(".facebook.com"))) {
      var m =
          java.util.regex.Pattern.compile("/(?:posts|videos|reel)/([0-9]+)(?:/|$)")
              .matcher(u.getPath());
      if (m.find()) canonical = m.group(1);
      if (canonical == null && u.getQuery() != null)
        for (String part : u.getQuery().split("&"))
          if (part.matches("(?:v|story_fbid)=[0-9]+"))
            canonical = part.substring(part.indexOf('=') + 1);
    }
    if (postId != null && !postId.isBlank()) {
      if (!postId.matches("[A-Za-z0-9_-]{1,100}") || canonical == null || !postId.equals(canonical))
        throw new ValidationException("Post ID must match the platform URL; otherwise omit postId");
    }
    if (canonical != null) return platform + ":ID:" + canonical;
    String path = u.getPath().replaceAll("/+$", "");
    // Strip known tracking parameters only; content-identifying query parameters are retained.
    String query = u.getRawQuery();
    if (query != null)
      query =
          Arrays.stream(query.split("&"))
              .filter(
                  v ->
                      !v.matches(
                          "(?i)(utm_[^=]*|fbclid|igshid|is_from_webapp|sender_device|web_id)($|=.*)"))
              .sorted()
              .collect(java.util.stream.Collectors.joining("&"));
    return platform
        + ":URL:"
        + u.getHost()
        + path
        + (query == null || query.isEmpty() ? "" : "?" + query);
  }

  @Transactional
  public JsonNode setupLegacy(UUID id, JsonNode body, String key) {
    body = canonical(body);
    var c = lock(id, true, Permission.MANAGE_OWN_COLLABORATION);
    String scope = "execution-plan:" + id;
    var cached = deals.replay(scope, key, body);
    if (cached != null) return cached;
    if (!"LEGACY".equals(c.get("execution_source"))
        || !Set.of("PLANNED", "IN_PROGRESS").contains(c.get("status")))
      throw conflict("Only unfinished legacy collaboration may record an execution plan");
    revision(c, "execution_revision", positive(body, "expectedRevision"));
    if (body.path("sourceDescription").asText().isBlank())
      throw new ValidationException("Legacy plan provenance required");
    snapshots.create(id, null, body.path("deliverables"), "BRAND_LEGACY_PLAN");
    audit.record(
        "LEGACY_EXECUTION_PLAN",
        "COLLABORATION",
        id,
        null,
        body,
        "Brand recorded existing terms; not historical consent");
    return deals.remembered(scope, key, body, view(id));
  }

  @Transactional
  public JsonNode submit(UUID id, UUID d, ExecutionDtos.Submission r, String key) {
    if (Permissions.actor().getRole() != Role.CREATOR) throw new ForbiddenException("Creator required");
    var c = lock(id, false, Permission.MANAGE_OWN_COLLABORATION);
    String scope = "submission:" + d;
    var old = replay(scope, key, r);
    if (old != null) return old;
    progress(c);
    var row = deliverable(id, d);
    revision(row, "revision", r.expectedRevision());
    if (!"CREATOR_PORTAL".equals(r.source()) || r.sourceDescription() != null)
      throw new ValidationException("Submission source/provenance does not match actor");
    if ("SUBMITTED".equals(row.get("status")))
      throw conflict("Review the pending submission before replacing it");
    long rev = ((Number) row.get("submission_revision")).longValue() + 1;
    s.db.update(
        "insert into collaboration_submission values(?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        d,
        rev,
        https(r.url()),
        actor(),
        r.source(),
        r.sourceDescription(),
        s.time());
    s.db.update(
        "update collaboration_deliverable set"
            + " revision=revision+1,submission_revision=?,status='SUBMITTED' where id=?",
        rev,
        d);
    changed(id, "CONTENT_SUBMITTED", Map.of("deliverableId", d, "submissionRevision", rev));
    return deals.remembered(scope, key, r, view(id));
  }

  @Transactional
  public JsonNode decide(UUID id, UUID d, ExecutionDtos.Decision r, String key) {
    var c = lock(id, true, Permission.MANAGE_OWN_COLLABORATION);
    String scope = "content-review:" + d;
    var old = deals.replay(scope, key, r);
    if (old != null) return old;
    progress(c);
    var row = deliverable(id, d);
    revision(row, "revision", r.expectedRevision());
    revision(row, "submission_revision", r.submissionRevision());
    if (!"SUBMITTED".equals(row.get("status"))) throw conflict("A pending submission is required");
    boolean approved = "APPROVE".equals(r.decision());
    if (approved) {
      var required = s.decode(row.get("acceptance_checklist"));
      Set<String> selected = new HashSet<>(r.completedChecklist());
      if (selected.size() != r.completedChecklist().size())
        throw new ValidationException("Duplicate checklist item");
      Set<String> expected = new HashSet<>();
      required.forEach(v -> expected.add(v.asText()));
      if (!selected.equals(expected))
        throw new ValidationException("Every required checklist item must be satisfied");
    } else {
      if (!"REQUEST_CHANGES".equals(r.decision()) || r.reason() == null || r.reason().isBlank())
        throw new ValidationException("Revision reason required");
      if (((Number) row.get("revision_rounds")).intValue()
          >= ((Number) row.get("max_revision_rounds")).intValue())
        throw conflict("Negotiated revision limit reached");
    }
    s.db.update(
        "insert into collaboration_content_review values(?,?,?,?,?::jsonb,?,?,?)",
        UUID.randomUUID(),
        d,
        r.submissionRevision(),
        r.decision(),
        s.encode(r.completedChecklist()),
        r.reason(),
        actor(),
        s.time());
    s.db.update(
        "update collaboration_deliverable set"
            + " revision=revision+1,status=?,revision_rounds=revision_rounds+? where id=?",
        approved ? "APPROVED" : "CHANGES_REQUESTED",
        approved ? 0 : 1,
        d);
    changed(
        id,
        approved ? "CONTENT_APPROVED" : "CONTENT_CHANGES_REQUESTED",
        Map.of("deliverableId", d, "submissionRevision", r.submissionRevision()));
    return deals.remembered(scope, key, r, view(id));
  }

  @Transactional
  public JsonNode publish(UUID id, UUID d, ExecutionDtos.Publication r, String key) {
    var c = lock(id, false, Permission.MANAGE_OWN_COLLABORATION);
    boolean brand = isBrand(c);
    if (brand != r.acknowledgeException() || (!brand && r.exceptionReason() != null
        && !r.exceptionReason().isBlank()))
      throw new ForbiddenException("Creator records publication and Brand acknowledges exceptions");
    String scope = "publication:" + d;
    var old = replay(scope, key, r);
    if (old != null) return old;
    progress(c);
    var row = deliverable(id, d);
    revision(row, "revision", r.expectedRevision());
    revision(row, "submission_revision", r.submissionRevision());
    if (r.submissionRevision() < 1) throw conflict("Submission required");
    var existing =
        s.optional(
            "select * from collaboration_publication where deliverable_id=? and"
                + " submission_revision=?",
            d,
            r.submissionRevision());
    String platform =
        s.one("select platform from creator where id=?", row.get("creator_id"))
            .get("platform")
            .toString();
    String url = https(r.url());
    boolean early = !"APPROVED".equals(row.get("status"));
    if (brand && (r.exceptionReason() == null || r.exceptionReason().isBlank()))
      throw new ValidationException("An exception acknowledgement requires a reason");
    if (existing.isPresent()) {
      var p = existing.get();
      if (!Boolean.TRUE.equals(p.get("early_publication"))
          || Boolean.TRUE.equals(p.get("exception_acknowledged"))
          || !r.acknowledgeException()
          || !url.equals(p.get("url"))
          || (r.postId() != null && !postKey(platform, url, r.postId()).equals(p.get("post_key"))))
        throw conflict("Publication already recorded or exception unavailable");
      s.db.update(
          "update collaboration_publication set"
              + " exception_acknowledged=true,exception_reason=?,exception_acknowledged_by=?,exception_acknowledged_at=?"
              + " where id=?",
          r.exceptionReason(),
          actor(),
          s.time(),
          p.get("id"));
    } else {
      if (brand) throw conflict("Creator must record the publication first");
      insertPublication(id, d, r.submissionRevision(), url, postKey(platform, url, r.postId()), early);
    }
    s.db.update("update collaboration_deliverable set revision=revision+1 where id=?", d);
    changed(
        id,
        "PUBLICATION_RECORDED",
        Map.of("deliverableId", d, "submissionRevision", r.submissionRevision()));
    return deals.remembered(scope, key, r, view(id));
  }

  private void insertPublication(UUID id, UUID deliverable, long submission, String url,
      String post, boolean early) {
    if (s.db.queryForObject(
            "select count(*) from collaboration_publication p join collaboration_deliverable d on"
                + " d.id=p.deliverable_id where d.collaboration_id=? and p.post_key=? and"
                + " p.deliverable_id<>?",
            Long.class, id, post, deliverable)
        > 0) throw conflict("Post already belongs to another deliverable");
    s.db.update(
        "insert into collaboration_publication(id,deliverable_id,submission_revision,url,post_key,"
            + "early_publication,exception_acknowledged,actor_id,created_at) values(?,?,?,?,?,?,false,?,?)",
        UUID.randomUUID(), deliverable, submission, url, post, early, actor(), s.time());
  }

  JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      var out = s.json.createObjectNode();
      var names = new TreeSet<String>();
      node.fieldNames().forEachRemaining(names::add);
      for (String name : names) out.set(name, canonical(node.get(name)));
      return out;
    }
    if (node.isArray()) {
      var out = s.json.createArrayNode();
      node.forEach(value -> out.add(canonical(value)));
      return out;
    }
    return node;
  }

  void fields(JsonNode body, Set<String> allowed) {
    if (!body.isObject()) throw new ValidationException("JSON object required");
    body.fieldNames()
        .forEachRemaining(
            k -> {
              if (!allowed.contains(k))
                throw new ValidationException("Unsupported outcome field: " + k);
            });
  }

  long positive(JsonNode body, String field) {
    var v = body.path(field);
    if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 1)
      throw new ValidationException(field + " must be a positive integer");
    return v.longValue();
  }

  private static final List<String> FINANCE_FIELDS = List.of("costs", "currency", "gmv", "revenue",
      "periodStart", "periodEnd", "financialEvidence", "financialConfirmed");

  private static final List<String> METRICS = List.of("views", "clicks", "likes", "comments", "shares");
  private static final Set<String> MEASUREMENT_FIELDS = Set.of(
      "deliverableId", "submissionRevision", "periodStart", "periodEnd", "evidenceUrl",
      "views", "clicks", "likes", "comments", "shares", "unavailableMetrics");

  @Transactional
  public JsonNode deliverableOutcome(UUID id, UUID deliverableId, JsonNode request, String key) {
    if (Permissions.actor().getRole() != Role.CREATOR) throw new ForbiddenException("Creator required");
    var c = lock(id, false, Permission.RECORD_OWN_OUTCOME);
    var body = canonical(request);
    String scope = "deliverable-outcome:" + id + ":" + deliverableId;
    if (body.toString().length() > 1000000) throw new ValidationException("Invalid outcome body");
    var allowed = new HashSet<>(MEASUREMENT_FIELDS);
    allowed.remove("deliverableId");
    allowed.addAll(List.of("expectedRevision", "url", "reason"));
    fields(body, allowed);
    var old = replay(scope, key, body);
    if (old != null) return old;
    progress(c);
    revision(c, "execution_revision", positive(body, "expectedRevision"));
    var unit = deliverable(id, deliverableId);
    long submission = positive(body, "submissionRevision");
    revision(unit, "submission_revision", submission);
    if (!"APPROVED".equals(unit.get("status"))) throw conflict("Deliverable must be approved before recording KPI");

    var previous = latestOutcome(id);
    var oldPayload = outcomePayload(previous);
    var post = s.json.createObjectNode().put("deliverableId", deliverableId.toString());
    copyFields(body, post, MEASUREMENT_FIELDS);
    requireCompleteMetrics(post);
    boolean editing = hasMeasurement(oldPayload, post);
    String reason = outcomeReason(body, editing, "Ghi nhận kết quả bài đăng lần đầu");

    var existing = publication(deliverableId, submission);
    if (existing.isEmpty()) {
      if (!body.path("url").isTextual()) throw new ValidationException("Publication URL required");
      String url = https(body.path("url").asText());
      String platform = s.one("select platform from creator where id=?", unit.get("creator_id"))
          .get("platform").toString();
      insertPublication(id, deliverableId, submission, url, postKey(platform, url, null), false);
      s.db.update("update collaboration_deliverable set revision=revision+1 where id=?", deliverableId);
      changed(id, "PUBLICATION_RECORDED", Map.of("deliverableId", deliverableId, "submissionRevision", submission));
    } else if (body.has("url")
        && (!body.path("url").isTextual() || !https(body.path("url").asText()).equals(existing.get().get("url")))) {
      throw conflict("Publication already recorded with a different link");
    }

    var payload = s.json.createObjectNode();
    copyFields(oldPayload, payload, FINANCE_FIELDS);
    copyFields(oldPayload, payload, List.of("resultNote"));
    mergeMeasurements(id, payload, oldPayload, List.of(post));
    return persistOutcome(id, previous, payload, reason, false, scope, key, body);
  }

  @Transactional
  public JsonNode outcome(UUID id, JsonNode body, String key) {
    if (Permissions.actor().getRole() != Role.CREATOR) throw new ForbiddenException("Creator required");
    return recordOutcome(id, canonical(body), key, false);
  }

  @Transactional
  public JsonNode finance(UUID id, JsonNode body, String key) {
    return recordOutcome(id, canonical(body), key, true);
  }

  private JsonNode recordOutcome(UUID id, JsonNode body, String key, boolean finance) {
    var c = lock(id, finance, Permission.RECORD_OWN_OUTCOME);
    String scope = (finance ? "outcome-finance:" : "outcome:") + id;
    if (finance && body.has("posts")) throw new ForbiddenException("Only Creator may record KPI");
    if (!finance && FINANCE_FIELDS.stream().anyMatch(body::has))
      throw new ForbiddenException("Only Brand may record finances");
    var old = replay(scope, key, body);
    if (old != null) return old;
    progress(c);
    revision(c, "execution_revision", positive(body, "expectedRevision"));
    if (!body.isObject() || body.toString().length() > 1000000)
      throw new ValidationException("Invalid outcome body");
    var allowed = new HashSet<>(Set.of("expectedRevision", "reason"));
    allowed.addAll(finance ? FINANCE_FIELDS : List.of("posts", "resultNote"));
    fields(body, allowed);
    if (body.hasNonNull("resultNote")
        && (!body.path("resultNote").isTextual()
            || body.path("resultNote").asText().length() > 2000))
      throw new ValidationException("Invalid outcome note");
    var previous = latestOutcome(id);
    var oldPayload = outcomePayload(previous);
    ObjectNode payload = s.json.createObjectNode();
    if (finance && !oldPayload.path("posts").isArray())
      throw conflict("Creator must record KPI before Brand adds finances");
    copyFields(finance ? oldPayload : body, payload, List.of("posts", "resultNote"));
    copyFields(finance ? body : oldPayload, payload, FINANCE_FIELDS);
    if (!finance && (!payload.path("posts").isArray() || payload.path("posts").isEmpty()))
      throw new ValidationException("Publication measurements required");
    boolean editing = finance && FINANCE_FIELDS.stream().anyMatch(oldPayload::has);
    if (!finance) {
      for (var post : payload.path("posts")) {
        requireCompleteMetrics(post);
        var unit = deliverable(id, measurementUnit(post));
        if (!"APPROVED".equals(unit.get("status")))
          throw conflict("Deliverable must be approved before recording KPI");
        editing |= hasMeasurement(oldPayload, post);
      }
      var submitted = new ArrayList<JsonNode>();
      payload.path("posts").forEach(submitted::add);
      mergeMeasurements(id, payload, oldPayload, submitted);
    }
    String reason = outcomeReason(body, editing,
        finance ? "Ghi nhận tài chính lần đầu" : "Ghi nhận kết quả bài đăng lần đầu");
    return persistOutcome(id, previous, payload, reason, finance, scope, key, body);
  }

  private Optional<Map<String, Object>> latestOutcome(UUID id) {
    return s.optional("select * from collaboration_outcome_revision where collaboration_id=? order by revision desc limit 1", id);
  }

  private JsonNode outcomePayload(Optional<Map<String, Object>> previous) {
    return previous.map(row -> s.decode(row.get("payload"))).orElse(s.json.nullNode());
  }

  private Optional<Map<String, Object>> publication(UUID deliverableId, long submission) {
    return s.optional("select * from collaboration_publication where deliverable_id=? and submission_revision=?",
        deliverableId, submission);
  }

  private boolean hasMeasurement(JsonNode payload, JsonNode post) {
    for (var existing : payload.path("posts")) {
      if (existing.path("deliverableId").equals(post.path("deliverableId"))
          && existing.path("submissionRevision").asLong() == post.path("submissionRevision").asLong()) return true;
    }
    return false;
  }

  private void mergeMeasurements(UUID id, ObjectNode payload, JsonNode oldPayload, List<JsonNode> submitted) {
    var currentRevisions = new HashMap<String, Long>();
    for (var row : rows(id))
      currentRevisions.put(row.get("id").toString(), ((Number) row.get("submission_revision")).longValue());
    var submittedIds = new HashSet<String>();
    for (var post : submitted) submittedIds.add(post.path("deliverableId").asText());
    var posts = payload.putArray("posts");
    for (var prior : oldPayload.path("posts")) {
      String priorId = prior.path("deliverableId").asText();
      if (!submittedIds.contains(priorId)
          && Objects.equals(currentRevisions.get(priorId), prior.path("submissionRevision").asLong())) {
        posts.add(prior);
      }
    }
    submitted.forEach(posts::add);
  }

  private UUID measurementUnit(JsonNode post) {
    try {
      return UUID.fromString(post.path("deliverableId").asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Deliverable ID required");
    }
  }

  private String outcomeReason(JsonNode body, boolean editing, String initialReason) {
    var reason = body.path("reason");
    if (reason.isTextual() && !reason.asText().isBlank() && reason.asText().length() <= 2000)
      return reason.asText().strip();
    if (!editing && (reason.isMissingNode() || reason.isNull())) return initialReason;
    throw new ValidationException("Outcome change reason required");
  }

  private void requireCompleteMetrics(JsonNode post) {
    if (!metricsDeclared(post)) throw new ValidationException(
        "Declare each KPI as a nonnegative integer or include its name in unavailableMetrics");
  }

  private boolean metricsDeclared(JsonNode post) {
    var unavailable = post.path("unavailableMetrics");
    Set<String> missing = new HashSet<>();
    if (!unavailable.isMissingNode()) {
      if (!unavailable.isArray()) return false;
      for (var metric : unavailable)
        if (!metric.isTextual() || !METRICS.contains(metric.asText()) || !missing.add(metric.asText()))
          return false;
    }
    for (String metric : METRICS) {
      var value = post.path(metric);
      if (missing.contains(metric)) {
        if (post.hasNonNull(metric)) return false;
      } else if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
        return false;
      }
    }
    return true;
  }

  private JsonNode persistOutcome(UUID id, Optional<Map<String, Object>> previous, ObjectNode payload,
      String reason, boolean finance, String scope, String key, JsonNode body) {
    Set<String> posts = new HashSet<>();
    Set<UUID> units = new HashSet<>();
    for (var p : payload.path("posts")) {
      fields(p, MEASUREMENT_FIELDS);
      UUID d = measurementUnit(p);
      var row = deliverable(id, d);
      long submission = positive(p, "submissionRevision");
      if (submission != ((Number) row.get("submission_revision")).longValue())
        throw conflict("Measurement refers to a superseded submission");
      var publication = publication(d, submission)
          .orElseThrow(() -> conflict("Publication must be recorded before KPI"));
      if (!units.add(d) || !posts.add(publication.get("post_key").toString()))
        throw new ValidationException("Duplicate publication measurement");
      if (p.hasNonNull("evidenceUrl")) https(p.path("evidenceUrl").asText());
    }
    if (payload.hasNonNull("financialEvidence")) https(payload.path("financialEvidence").asText());
    if (payload.has("financialConfirmed") && !payload.path("financialConfirmed").isBoolean())
      throw new ValidationException("financialConfirmed must be boolean");
    var calculation = OutcomeFinance.calculate(s.json, payload);
    if (finance && payload.path("financialConfirmed").asBoolean()
        && (!calculation.path("actualCost").isNumber()
            || calculation.path("actualCost").decimalValue().signum() <= 0))
      throw new ValidationException("Confirmed actual cost must be positive");
    long rev = previous.map(v -> ((Number) v.get("revision")).longValue() + 1).orElse(1L);
    s.db.update(
        "insert into"
            + " collaboration_outcome_revision(id,collaboration_id,revision,payload,calculation,actor_id,reason,created_at)"
            + " values(?,?,?,?::jsonb,?::jsonb,?,?,?)",
        UUID.randomUUID(),
        id,
        rev,
        payload.toString(),
        calculation.toString(),
        actor(),
        reason,
        s.time());
    changed(id, finance ? "OUTCOME_FINANCE_RECORDED" : "OUTCOME_RECORDED", Map.of("outcomeRevision", rev));
    return deals.remembered(scope, key, body, view(id));
  }

  private void copyFields(JsonNode source, ObjectNode target, Collection<String> fields) {
    for (var field : fields) if (source.has(field)) target.set(field, source.get(field));
  }

  @Transactional
  public JsonNode accept(UUID id, ExecutionDtos.Accept r, String key) {
    var c = lock(id, true, Permission.RECORD_OWN_OUTCOME);
    String scope = "outcome-accept:" + id;
    var old = deals.replay(scope, key, r);
    if (old != null) return old;
    progress(c);
    revision(c, "execution_revision", r.expectedRevision());
    var latest =
        s.optional(
                "select * from collaboration_outcome_revision where collaboration_id=? order by"
                    + " revision desc limit 1",
                id)
            .orElseThrow(() -> conflict("Record an outcome before acceptance"));
    revision(latest, "revision", r.outcomeRevision());
    var payload = s.decode(latest.get("payload"));
    var calc = s.decode(latest.get("calculation"));
    if (!payload.path("financialConfirmed").asBoolean()
        || !calc.path("actualCost").isNumber()
        || calc.path("actualCost").decimalValue().signum() <= 0
        || !"VND".equals(payload.path("currency").asText()))
      throw conflict("Brand must confirm positive VND actual costs");
    var units = rows(id);
    if (units.isEmpty()) throw conflict("Execution plan required");
    Set<String> measured = new HashSet<>();
    payload
        .path("posts")
        .forEach(
            p -> {
              if (metricsDeclared(p))
                measured.add(p.path("deliverableId").asText() + ":" + p.path("submissionRevision").asLong());
            });
    for (var d : units) {
      if (!"APPROVED".equals(d.get("status"))) throw conflict("All deliverables must be approved");
      var p =
          s.optional(
              "select * from collaboration_publication where deliverable_id=? and"
                  + " submission_revision=?",
              d.get("id"),
              d.get("submission_revision"));
      if (p.isEmpty()
          || Boolean.TRUE.equals(p.get().get("early_publication"))
              && !Boolean.TRUE.equals(p.get().get("exception_acknowledged"))
          || !measured.contains(d.get("id") + ":" + d.get("submission_revision")))
        throw conflict("Every current publication must have complete KPI and exceptions must be acknowledged");
    }
    s.db.update(
        "insert into"
            + " collaboration_outcome_acceptance(id,collaboration_id,outcome_revision,actor_id,accepted_at)"
            + " values(?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        r.outcomeRevision(),
        actor(),
        s.time());
    s.db.update("update collaboration set status='AWAITING_REVIEW' where id=?", id);
    changed(id, "OUTCOME_ACCEPTED", Map.of("outcomeRevision", r.outcomeRevision()));
    return deals.remembered(scope, key, r, view(id));
  }

  @Transactional
  public JsonNode reopen(UUID id, ExecutionDtos.Reopen r, String key) {
    var c = lock(id, true, Permission.RECORD_OWN_OUTCOME);
    String scope = "outcome-reopen:" + id;
    var old = deals.replay(scope, key, r);
    if (old != null) return old;
    revision(c, "execution_revision", r.expectedRevision());
    if (!"AWAITING_REVIEW".equals(c.get("status")))
      throw conflict("Only accepted outcome awaiting review may reopen");
    s.db.update(
        "update collaboration_outcome_acceptance set reopened_at=?,reopen_reason=? where"
            + " collaboration_id=? and reopened_at is null",
        s.time(),
        r.reason(),
        id);
    s.db.update("update collaboration set status='IN_PROGRESS' where id=?", id);
    changed(id, "OUTCOME_REOPENED");
    return deals.remembered(scope, key, r, view(id));
  }

  @Transactional
  public JsonNode review(UUID id, ExecutionDtos.FinalReview r, String key) {
    var c = lock(id, true, Permission.RECORD_OWN_CREATOR_REVIEW);
    String scope = "execution-final-review:" + id;
    var old = deals.replay(scope, key, r);
    if (old != null) return old;
    revision(c, "execution_revision", r.expectedRevision());
    if (!"AWAITING_REVIEW".equals(c.get("status")))
      throw conflict("Accept outcome before final review");
    var accepted =
        s.one(
            "select * from collaboration_outcome_acceptance where collaboration_id=? and"
                + " reopened_at is null",
            id);
    revision(accepted, "outcome_revision", r.outcomeRevision());
    var out =
        s.one(
            "select * from collaboration_outcome_revision where collaboration_id=? and revision=?",
            id,
            r.outcomeRevision());
    var calc = s.decode(out.get("calculation"));
    var data = s.decode(out.get("payload"));
    UUID outcome =
        s.optional("select id from campaign_outcome where collaboration_id=?", id)
            .map(v -> uuid(v.get("id")))
            .orElseGet(UUID::randomUUID);
    s.db.update(
        "insert into"
            + " campaign_outcome(id,collaboration_id,recorded_at,actual_fee,currency,revenue,roi,evidence_quality,completed_at,views,clicks,likes,comments,shares)"
            + " values(?,?,?,?,'VND',?,?,'SELF_REPORTED',?,?,?,?,?,?) on conflict(collaboration_id)"
            + " do update set"
            + " actual_fee=excluded.actual_fee,currency='VND',revenue=excluded.revenue,roi=excluded.roi,evidence_quality='SELF_REPORTED',recorded_at=excluded.recorded_at,completed_at=excluded.completed_at,views=excluded.views,clicks=excluded.clicks,likes=excluded.likes,comments=excluded.comments,shares=excluded.shares",
        outcome,
        id,
        s.time(),
        decimal(calc, "actualCost"),
        decimal(data, "revenue"),
        legacyRoi(calc),
        s.time(),
        longMetric(calc, "views"),
        longMetric(calc, "clicks"),
        longMetric(calc, "likes"),
        longMetric(calc, "comments"),
        longMetric(calc, "shares"));
    UUID review = UUID.randomUUID();
    s.db.update(
        "insert into"
            + " creator_review(id,outcome_id,collaboration_id,reviewed_by_user_id,overall_rating,brand_fit_rating,would_collaborate_again,note,reviewed_at,review_tags,accepted_outcome_revision)"
            + " values(?,?,?,?,?,?,?,?,?,?::jsonb,?)",
        review,
        outcome,
        id,
        actor(),
        r.overallRating(),
        r.brandFitRating(),
        r.wouldCollaborateAgain(),
        r.note(),
        s.time(),
        s.encode(r.reviewTags()),
        r.outcomeRevision());
    var primaryPublication =
        s.optional(
            "select p.url from collaboration_publication p join collaboration_deliverable d on"
                + " d.id=p.deliverable_id where d.collaboration_id=? and d.creator_id=? and"
                + " p.submission_revision=d.submission_revision order by d.id limit 1",
            id,
            c.get("creator_id"));
    s.db.update(
        "update collaboration set status='COMPLETED',completed_at=?,published_content_url=? where"
            + " id=?",
        s.time(),
        primaryPublication.map(p -> p.get("url")).orElse(null),
        id);
    var proof =
        s.json
            .createObjectNode()
            .put("reviewerAuthenticated", true)
            .put("brandAccepted", true)
            .put("metricSource", "SELF_REPORTED")
            .put("outcomeRevision", r.outcomeRevision())
            .put("overallRating", r.overallRating())
            .put("brandFitRating", r.brandFitRating())
            .put("wouldCollaborateAgain", r.wouldCollaborateAgain())
            .put("m1Policy", "M1_POLICY_NOT_IMPLEMENTED");
    proof.set("outcome", data);
    proof.set("calculation", calc);
    s.db.update(
        "insert into"
            + " historical_campaign(id,creator_id,master_id,collaboration_id,outcome_id,evidence_type,evidence_value,quality,recorded_at,source_review_id,source_revision,provenance)"
            + " values(?,?,?,?,?,'COLLABORATION_REVIEW',?::jsonb,'SELF_REPORTED',?,?,1,'BRAND_ACCEPTED')",
        UUID.randomUUID(),
        c.get("creator_id"),
        c.get("master_id"),
        id,
        outcome,
        proof.toString(),
        s.time(),
        review);
    changed(
        id,
        "COLLABORATION_REVIEWED",
        Map.of("reviewId", review, "outcomeRevision", r.outcomeRevision()));
    return deals.remembered(scope, key, r, view(id));
  }

  @Transactional
  public JsonNode legacyReview(UUID id, ExecutionDtos.LegacyReview r, String key) {
    var c = lock(id, true, Permission.RECORD_OWN_CREATOR_REVIEW);
    String scope = "execution-legacy-review:" + id;
    var old = deals.replay(scope, key, r);
    if (old != null) return old;
    revision(c, "execution_revision", r.expectedRevision());
    if (!"LEGACY".equals(c.get("execution_source")) || !"COMPLETED".equals(c.get("status")))
      throw conflict("Historical completed collaboration required");
    if (s.db.queryForObject(
            "select count(*) from creator_review where collaboration_id=?", Long.class, id)
        > 0) throw conflict("Final review already exists");
    var outcome = s.one("select * from campaign_outcome where collaboration_id=?", id);
    UUID review = UUID.randomUUID();
    s.db.update(
        "insert into"
            + " creator_review(id,outcome_id,collaboration_id,reviewed_by_user_id,overall_rating,brand_fit_rating,would_collaborate_again,note,reviewed_at,review_tags)"
            + " values(?,?,?,?,?,?,?,?,?,?::jsonb)",
        review,
        outcome.get("id"),
        id,
        actor(),
        r.overallRating(),
        r.brandFitRating(),
        r.wouldCollaborateAgain(),
        r.note(),
        s.time(),
        s.encode(r.reviewTags()));
    var proof =
        s.json
            .createObjectNode()
            .put("legacyWorkflow", true)
            .put("reviewerAuthenticated", true)
            .put("brandAccepted", false)
            .put("metricSource", "LEGACY_UNVERIFIED")
            .put("overallRating", r.overallRating())
            .put("brandFitRating", r.brandFitRating());
    s.db.update(
        "insert into"
            + " historical_campaign(id,creator_id,master_id,collaboration_id,outcome_id,evidence_type,evidence_value,quality,recorded_at,source_review_id,source_revision,provenance)"
            + " values(?,?,?,?,?,'COLLABORATION_REVIEW',?::jsonb,'SELF_REPORTED',?,?,1,'LEGACY_UNVERIFIED')",
        UUID.randomUUID(),
        c.get("creator_id"),
        c.get("master_id"),
        id,
        outcome.get("id"),
        proof.toString(),
        s.time(),
        review);
    changed(id, "LEGACY_COLLABORATION_REVIEWED");
    return deals.remembered(scope, key, r, view(id));
  }

  java.math.BigDecimal decimal(JsonNode node, String key) {
    return node.path(key).isNumber() ? node.path(key).decimalValue() : null;
  }

  java.math.BigDecimal legacyRoi(JsonNode node) {
    var value = decimal(node, "roi");
    return value != null && value.abs().compareTo(new java.math.BigDecimal("99999999.9999")) > 0
        ? null
        : value;
  }

  Long longMetric(JsonNode node, String key) {
    return node.path(key).isNumber() ? node.path(key).longValue() : null;
  }
}
