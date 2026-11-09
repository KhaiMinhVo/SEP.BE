package com.influencermatch.backend.cooperation;

import static com.influencermatch.backend.cooperation.DealStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.net.URI;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CollaborationExecution {
  @org.springframework.beans.factory.annotation.Autowired
  private LearningEvidenceWriter learningEvidence;

  final DealStore s;
  final DealService deals;
  final ExecutionSnapshot snapshots;
  final AuditService audit;
  final ExecutionHistoryReader history;

  UUID actor() {
    return Permissions.actor().getId();
  }

  boolean isBrand(Map<String, Object> c) {
    return actor().equals(c.get("brand_user_id"));
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
      if (brandOnly) throw new ForbiddenException("Brand owner required");
      Permissions.require(Permission.RESPOND_OWN_COOPERATION);
      deals.fresh(Permission.RESPOND_OWN_COOPERATION);
      if (!deals.canAccessCollaboration(actor(), uuid(c.get("id"))))
        throw new ForbiddenException("Current ownership of every channel required");
    }
  }

  Map<String, Object> lock(UUID id, boolean brandOnly, Permission permission) {
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

  void progress(Map<String, Object> c) {
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

  Map<String, Object> view(UUID id) {
    return history.snapshot(id);
  }

  List<Map<String, Object>> rows(UUID id) {
    return history.units(id);
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public JsonNode read(UUID id) {
    var c = initial(id);
    access(c, false, Permission.MANAGE_OWN_COLLABORATION);
    return s.json.valueToTree(view(id));
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
    var c = lock(id, false, Permission.MANAGE_OWN_COLLABORATION);
    String scope = "submission:" + d;
    var old = deals.replay(scope, key, r);
    if (old != null) return old;
    progress(c);
    var row = deliverable(id, d);
    revision(row, "revision", r.expectedRevision());
    boolean brand = isBrand(c);
    if (!(brand ? "BRAND_EXTERNAL" : "CREATOR_PORTAL").equals(r.source())
        || brand && (r.sourceDescription() == null || r.sourceDescription().isBlank()))
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
    String scope = "publication:" + d;
    var old = deals.replay(scope, key, r);
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
    String url = https(r.url()), post = postKey(platform, url, r.postId());
    boolean early = !"APPROVED".equals(row.get("status"));
    if (r.acknowledgeException()
        && (!isBrand(c) || r.exceptionReason() == null || r.exceptionReason().isBlank()))
      throw new ValidationException("Only Brand may acknowledge an exception with a reason");
    if (existing.isPresent()) {
      var p = existing.get();
      if (!Boolean.TRUE.equals(p.get("early_publication"))
          || Boolean.TRUE.equals(p.get("exception_acknowledged"))
          || !r.acknowledgeException()
          || !post.equals(p.get("post_key"))) throw conflict("Publication already recorded");
      s.db.update(
          "update collaboration_publication set"
              + " exception_acknowledged=true,exception_reason=?,exception_acknowledged_by=?,exception_acknowledged_at=?"
              + " where id=?",
          r.exceptionReason(),
          actor(),
          s.time(),
          p.get("id"));
    } else {
      if (s.db.queryForObject(
              "select count(*) from collaboration_publication p join collaboration_deliverable d on"
                  + " d.id=p.deliverable_id where d.collaboration_id=? and p.post_key=? and"
                  + " p.deliverable_id<>?",
              Long.class,
              id,
              post,
              d)
          > 0) throw conflict("Post already belongs to another deliverable");
      s.db.update(
          "insert into"
              + " collaboration_publication(id,deliverable_id,submission_revision,url,post_key,early_publication,exception_acknowledged,exception_reason,actor_id,created_at,exception_acknowledged_by,exception_acknowledged_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          d,
          r.submissionRevision(),
          url,
          post,
          early,
          r.acknowledgeException(),
          r.exceptionReason(),
          actor(),
          s.time(),
          r.acknowledgeException() ? actor() : null,
          r.acknowledgeException() ? s.time() : null);
    }
    s.db.update("update collaboration_deliverable set revision=revision+1 where id=?", d);
    changed(
        id,
        "PUBLICATION_RECORDED",
        Map.of("deliverableId", d, "submissionRevision", r.submissionRevision()));
    return deals.remembered(scope, key, r, view(id));
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

  @Transactional
  public JsonNode outcome(UUID id, JsonNode body, String key) {
    body = canonical(body);
    var c = lock(id, false, Permission.RECORD_OWN_OUTCOME);
    String scope = "outcome:" + id;
    var old = deals.replay(scope, key, body);
    if (old != null) return old;
    progress(c);
    revision(c, "execution_revision", positive(body, "expectedRevision"));
    String reason = body.path("reason").asText();
    if (!body.path("reason").isTextual() || reason.isBlank() || reason.length() > 2000)
      throw new ValidationException("Outcome change reason required");
    if (!body.isObject() || body.toString().length() > 1000000)
      throw new ValidationException("Invalid outcome body");
    fields(
        body,
        Set.of(
            "expectedRevision",
            "reason",
            "posts",
            "costs",
            "currency",
            "gmv",
            "revenue",
            "periodStart",
            "periodEnd",
            "financialEvidence",
            "financialConfirmed",
            "resultNote"));
    if (body.hasNonNull("resultNote")
        && (!body.path("resultNote").isTextual()
            || body.path("resultNote").asText().length() > 2000))
      throw new ValidationException("Invalid outcome note");
    ObjectNode payload = body.deepCopy();
    boolean brand = isBrand(c);
    var previous =
        s.optional(
            "select * from collaboration_outcome_revision where collaboration_id=? order by"
                + " revision desc limit 1",
            id);
    for (String f :
        List.of(
            "costs",
            "currency",
            "gmv",
            "revenue",
            "periodStart",
            "periodEnd",
            "financialEvidence",
            "financialConfirmed")) {
      if (!brand) {
        if (body.has(f)) throw new ForbiddenException("Only Brand may record finances");
        if (previous.isPresent()) {
          var oldPayload = s.decode(previous.get().get("payload"));
          if (oldPayload.has(f)) payload.set(f, oldPayload.get(f));
        }
      }
    }
    Set<String> posts = new HashSet<>();
    Set<UUID> units = new HashSet<>();
    for (var p : payload.path("posts")) {
      fields(
          p,
          Set.of(
              "deliverableId",
              "submissionRevision",
              "periodStart",
              "periodEnd",
              "views",
              "clicks",
              "likes",
              "comments",
              "shares",
              "evidenceUrl"));
      UUID d;
      try {
        d = UUID.fromString(p.path("deliverableId").asText());
      } catch (Exception e) {
        throw new ValidationException("Deliverable ID required");
      }
      var row = deliverable(id, d);
      long submission = positive(p, "submissionRevision");
      var publication =
          s.one(
              "select * from collaboration_publication where deliverable_id=? and"
                  + " submission_revision=?",
              d,
              submission);
      if (!units.add(d) || !posts.add(publication.get("post_key").toString()))
        throw new ValidationException("Duplicate publication measurement");
      if (p.hasNonNull("evidenceUrl")) https(p.path("evidenceUrl").asText());
      if (submission != ((Number) row.get("submission_revision")).longValue())
        throw conflict("Measurement refers to a superseded submission");
    }
    if (payload.hasNonNull("financialEvidence")) https(payload.path("financialEvidence").asText());
    if (payload.has("financialConfirmed") && !payload.path("financialConfirmed").isBoolean())
      throw new ValidationException("financialConfirmed must be boolean");
    var calculation = OutcomeFinance.calculate(s.json, payload);
    if (body.path("financialConfirmed").asBoolean() && !isBrand(c))
      throw new ForbiddenException("Brand confirmation required");
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
    changed(id, "OUTCOME_RECORDED", Map.of("outcomeRevision", rev));
    return deals.remembered(scope, key, body, view(id));
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
            p ->
                measured.add(
                    p.path("deliverableId").asText()
                        + ":"
                        + p.path("submissionRevision").asLong()));
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
        throw conflict("Every current publication must be measured and exceptions acknowledged");
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
            .put(
                "m1Policy",
                c.get("learning_context") == null
                    ? "LEGACY_NO_LEARNING_SNAPSHOT"
                    : com.influencermatch.backend.recommendation.learning.LearningPolicy.VERSION);
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
    learningEvidence.review(id, review);
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
