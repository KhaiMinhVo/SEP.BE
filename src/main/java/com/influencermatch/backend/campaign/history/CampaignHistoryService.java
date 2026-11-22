package com.influencermatch.backend.campaign.history;

import static com.influencermatch.backend.campaign.history.HistoryDtos.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.campaign.service.*;
import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.cooperation.ExecutionHistoryReader;
import com.influencermatch.backend.cooperation.CollaborationVideo;
import com.influencermatch.backend.cooperation.CollaborationVideoStorage;
import com.influencermatch.backend.cooperation.CollaborationExecution;
import com.influencermatch.backend.cooperation.OutcomeFinance;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CampaignHistoryService {
  final HistoryQueries q;
  final CampaignService campaigns;
  final CampaignRepository campaignRepository;
  final ExecutionHistoryReader execution;
  final CollaborationVideo videos;
  final AuditService audit;
  final Clock clock;
  @jakarta.persistence.PersistenceContext EntityManager em;

  public PageResponse<CampaignItem> list(
      CampaignStatus status,
      Platform platform,
      LocalDate from,
      LocalDate to,
      String search,
      int page,
      int size) {
    q.access(false);
    HistoryQueries.page(page, size);
    if (from != null && to != null && from.isAfter(to))
      throw new ValidationException("createdFrom must not exceed createdTo");
    if (search != null && search.length() > 200)
      throw new ValidationException("q may contain at most 200 characters");
    String where =
        " from campaign c join brand_profile b on b.id=c.brand_profile_id where b.user_id=?";
    var args = new ArrayList<Object>();
    args.add(q.actor());
    if (status != null) {
      where += " and c.status=?";
      args.add(status.name());
    }
    if (platform != null) {
      where += " and ?=any(c.platforms)";
      args.add(platform.name());
    }
    if (from != null) {
      where += " and c.created_at::date>=?";
      args.add(java.sql.Date.valueOf(from));
    }
    if (to != null) {
      where += " and c.created_at::date<=?";
      args.add(java.sql.Date.valueOf(to));
    }
    if (search != null && !search.isBlank()) {
      where += " and c.name ilike ? escape '!'";
      args.add("%" + search.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    }
    long count = q.db.queryForObject("select count(*)" + where, Long.class, args.toArray());
    var paging = new ArrayList<>(args);
    paging.add(size);
    paging.add((long) page * size);
    var rows =
        q.db.queryForList(
            "select c.*,(select count(*) from collaboration x where x.campaign_id=c.id)"
                + " collaboration_count,(select count(*) from collaboration x where"
                + " x.campaign_id=c.id and x.status='COMPLETED') completed_count"
                + where
                + " order by c.created_at desc,c.id desc limit ? offset ?",
            paging.toArray());
    var items =
        rows.stream()
            .map(
                r ->
                    new CampaignItem(
                        q.uuid(r.get("id")),
                        r.get("name").toString(),
                        CampaignStatus.valueOf(r.get("status").toString()),
                        q.local(r.get("created_at")),
                        q.local(r.get("updated_at")),
                        q.strings(r.get("platforms")),
                        q.number(r.get("collaboration_count")),
                        q.number(r.get("completed_count"))))
            .toList();
    return q.page(items, page, size, count);
  }

  public Detail detail(UUID id, Authentication auth) {
    q.access(false);
    q.owned(id);
    var current = campaigns.get(id, auth);
    var counts = new LinkedHashMap<String, Long>();
    for (String stage : List.of("CONTACTED", "NEGOTIATING", "CONFIRMED", "DECLINED", "CANCELLED"))
      counts.put(stage, 0L);
    for (var r :
        q.db.queryForList(
            "select status,count(*) n from cooperation_deal where campaign_id=? group by status",
            id)) counts.put(r.get("status").toString(), q.number(r.get("n")));
    var context = q.latestContext(id).map(q::context).orElse(null);
    return new Detail(
        current,
        "CURRENT_CAMPAIGN_RECORD",
        context,
        context == null ? "MISSING" : "AVAILABLE",
        counts,
        q.report(id),
        financialSummaries(id),
        q.origin(id));
  }

  @Transactional(readOnly = false, isolation = Isolation.READ_COMMITTED)
  public CampaignFinancialSummary recordFinancialSummary(
      UUID id, CampaignFinancialSummaryRequest request, String key) {
    q.access(false);
    Permissions.require(Permission.RECORD_OWN_OUTCOME);
    q.owned(id);
    q.one("select id from \"user\" where id=? for update", q.actor());
    var campaign = q.one("select * from campaign where id=? for update", id);
    q.access(false);
    q.owned(id);
    if (!List.of("ACTIVE", "COMPLETED", "CLOSED").contains(campaign.get("status").toString()))
      throw new ConflictException(
          ErrorCode.INVALID_CAMPAIGN_TRANSITION,
          "Campaign financial summary requires an active or finished Campaign");
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
      throw new ValidationException("Idempotency-Key required (1-100 safe characters)");
    if ((request.gmv() == null && request.revenue() == null)
        || request.periodEnd().isBefore(request.periodStart())
        || request.sourceDescription().isBlank())
      throw new ValidationException("Campaign total, source and valid measurement period required");
    if (request.gmv() != null) OutcomeFinance.amount(q.json.valueToTree(request.gmv()));
    if (request.revenue() != null) OutcomeFinance.amount(q.json.valueToTree(request.revenue()));
    if (request.evidenceUrl() != null && !request.evidenceUrl().isBlank())
      CollaborationExecution.https(request.evidenceUrl());

    var fingerprint = HistoryBrief.canonical(q.json, q.json.valueToTree(request)).toString();
    String requestHash = hash(fingerprint);
    var priorRequest = q.optional(
        "select revision,request_hash from campaign_financial_summary_revision where campaign_id=?"
            + " and actor_id=? and request_key=?", id, q.actor(), key);
    if (priorRequest.isPresent()) {
      if (!requestHash.equals(priorRequest.get().get("request_hash")))
        throw new ConflictException(
            ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency key has different content");
      var saved = q.one("select * from campaign_financial_summary_revision where campaign_id=? and revision=?",
          id, priorRequest.get().get("revision"));
      return financialSummary(id, q.report(id), saved);
    }

    long currentRevision = q.db.queryForObject(
        "select coalesce(max(revision),0) from campaign_financial_summary_revision where campaign_id=?",
        Long.class, id);
    if (currentRevision != request.expectedRevision())
      throw new ConflictException(
          ErrorCode.CAMPAIGN_REVISION_CONFLICT, "Campaign financial summary changed; reload it");
    String reason = request.reason() == null || request.reason().isBlank()
        ? currentRevision == 0 ? "Brand ghi nhận tổng Campaign lần đầu" : null
        : request.reason().strip();
    if (reason == null)
      throw new ValidationException("A reason is required when changing a saved Campaign summary");

    var payload = q.json.createObjectNode();
    if (request.gmv() == null) payload.putNull("gmv"); else payload.put("gmv", request.gmv());
    if (request.revenue() == null) payload.putNull("revenue"); else payload.put("revenue", request.revenue());
    payload.put("currency", "VND");
    payload.put("periodStart", request.periodStart().toString());
    payload.put("periodEnd", request.periodEnd().toString());
    payload.put("sourceDescription", request.sourceDescription().strip());
    if (request.evidenceUrl() == null || request.evidenceUrl().isBlank()) payload.putNull("evidenceUrl");
    else payload.put("evidenceUrl", CollaborationExecution.https(request.evidenceUrl()));
    var calculation = calculateCampaignFinancialSummary(id, q.report(id), payload);
    long revision = currentRevision + 1;
    q.db.update(
        "insert into campaign_financial_summary_revision(campaign_id,revision,request_key,request_hash,"
            + "payload,calculation,actor_id,reason,created_at) values(?,?,?,?,?::jsonb,?::jsonb,?,?,?)",
        id, revision, key, requestHash, payload.toString(), calculation.toString(),
        q.actor(), reason, java.sql.Timestamp.from(clock.instant()));
    audit.record("CAMPAIGN_FINANCIAL_SUMMARY_RECORDED", "Campaign", id, null,
        Map.of("revision", revision, "sourceDescription", request.sourceDescription().strip()),
        reason);
    return financialSummaries(id).getFirst();
  }

  private List<CampaignFinancialSummary> financialSummaries(UUID id) {
    var report = q.report(id);
    return q.db.queryForList(
        "select * from campaign_financial_summary_revision where campaign_id=?"
            + " order by revision desc limit 20", id).stream()
        .map(row -> financialSummary(id, report, row)).toList();
  }

  private CampaignFinancialSummary financialSummary(
      UUID id, Report report, Map<String, Object> row) {
    var payload = q.decode(row.get("payload"));
    var calculation = q.decode(row.get("calculation"));
    var unavailable = new LinkedHashMap<String, List<String>>();
    var reasons = calculation.path("unavailableReasons");
    if (reasons.isObject()) reasons.fields().forEachRemaining(entry -> {
      var values = new ArrayList<String>();
      entry.getValue().forEach(value -> values.add(value.asText()));
      unavailable.put(entry.getKey(), List.copyOf(values));
    });
    return new CampaignFinancialSummary(
        q.number(row.get("revision")),
        decimal(payload, "gmv"),
        decimal(payload, "revenue"),
        payload.path("currency").asText("VND"),
        Instant.parse(payload.path("periodStart").asText()),
        Instant.parse(payload.path("periodEnd").asText()),
        payload.path("sourceDescription").asText(),
        payload.path("evidenceUrl").isTextual() ? payload.path("evidenceUrl").asText() : null,
        q.uuid(row.get("actor_id")),
        row.get("reason").toString(),
        q.instant(row.get("created_at")),
        calculation.path("formulaVersion").asText(),
        decimal(calculation, "actualCost"),
        decimal(calculation, "roas"),
        decimal(calculation, "roi"),
        unavailable);
  }

  private ObjectNode calculateCampaignFinancialSummary(UUID id, Report report, JsonNode payload) {
    var result = q.json.createObjectNode().put("formulaVersion", "campaign-financial-summary-v1");
    var reasons = q.json.createObjectNode();
    var cost = report.metrics().get("actualCost");
    BigDecimal actualCost = cost != null && "AVAILABLE".equals(cost.availability()) ? cost.value() : null;
    if (actualCost == null) result.putNull("actualCost"); else result.put("actualCost", actualCost);
    var shared = new TreeSet<String>();
    if (cost != null && !"AVAILABLE".equals(cost.availability())) shared.addAll(cost.unavailableReasons());
    if (cost == null) shared.add("MISSING_ACTUAL_COST");
    if (!campaignPeriodsMatch(id, payload.path("periodStart").asText(), payload.path("periodEnd").asText()))
      shared.add("INCONSISTENT_FINANCIAL_PERIOD");
    if (actualCost != null && actualCost.signum() <= 0) shared.add("INVALID_DENOMINATOR");

    var roasReasons = new TreeSet<>(shared);
    var roiReasons = new TreeSet<>(shared);
    BigDecimal gmv = decimal(payload, "gmv");
    BigDecimal revenue = decimal(payload, "revenue");
    if (gmv == null) roasReasons.add("MISSING_CAMPAIGN_GMV");
    if (revenue == null) roiReasons.add("MISSING_CAMPAIGN_REVENUE");
    BigDecimal roas = ratio(gmv, actualCost, roasReasons, false);
    BigDecimal roi = ratio(revenue, actualCost, roiReasons, true);
    putNullable(result, "roas", roas);
    putNullable(result, "roi", roi);
    reasons.set("roas", q.json.valueToTree(roasReasons));
    reasons.set("roi", q.json.valueToTree(roiReasons));
    result.set("unavailableReasons", reasons);
    return result;
  }

  private boolean campaignPeriodsMatch(UUID id, String start, String end) {
    var rows = q.db.queryForList("""
        select o.payload from collaboration c
        join campaign ca on ca.id=c.campaign_id join brand_profile b on b.id=ca.brand_profile_id
        join collaboration_outcome_acceptance a on a.collaboration_id=c.id and a.reopened_at is null
        join collaboration_outcome_revision o on o.collaboration_id=c.id and o.revision=a.outcome_revision
        left join creator_review r on r.collaboration_id=c.id
        join campaign_outcome compat on compat.collaboration_id=c.id
        where c.campaign_id=? and c.status='COMPLETED' and a.actor_id=b.user_id
          and ((r.reviewed_by_user_id=b.user_id and r.outcome_id=compat.id
                and r.accepted_outcome_revision=a.outcome_revision)
               or c.completion_method='WITHOUT_REVIEW')
          and o.source in ('SELF_REPORTED','BRAND_EXTERNAL_REPORTED') and c.execution_source<>'LEGACY'
        """, id);
    for (var row : rows) {
      var payload = q.decode(row.get("payload"));
      try {
        if (!Instant.parse(start).equals(Instant.parse(payload.path("periodStart").asText()))
            || !Instant.parse(end).equals(Instant.parse(payload.path("periodEnd").asText())))
          return false;
      } catch (RuntimeException e) {
        return false;
      }
    }
    return !rows.isEmpty();
  }

  private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator, Set<String> reasons,
      boolean subtractCost) {
    if (!reasons.isEmpty() || numerator == null || denominator == null || denominator.signum() <= 0)
      return null;
    return (subtractCost ? numerator.subtract(denominator) : numerator)
        .divide(denominator, 4, java.math.RoundingMode.HALF_UP);
  }

  private void putNullable(ObjectNode target, String field, BigDecimal value) {
    if (value == null) target.putNull(field); else target.put(field, value);
  }

  private BigDecimal decimal(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.decimalValue();
  }

  public PageResponse<Context> contexts(UUID id, int page, int size) {
    q.access(false);
    q.owned(id);
    HistoryQueries.page(page, size);
    long count =
        q.db.queryForObject(
            "select count(*) from campaign_context where campaign_id=?", Long.class, id);
    var rows =
        q.db.queryForList(
            "select * from campaign_context where campaign_id=? order by context_version desc,id"
                + " desc limit ? offset ?",
            id,
            size,
            (long) page * size);
    return q.page(rows.stream().map(q::context).toList(), page, size, count);
  }

  public PageResponse<CollaborationItem> collaborations(
      UUID id, CollaborationStatus status, int page, int size) {
    q.access(false);
    q.owned(id);
    HistoryQueries.page(page, size);
    String filter = status == null ? "" : " and c.status=?";
    var args = new ArrayList<Object>();
    args.add(id);
    if (status != null) args.add(status.name());
    long count =
        q.db.queryForObject(
            "select count(*) from collaboration c where c.campaign_id=?" + filter,
            Long.class,
            args.toArray());
    var paging = new ArrayList<>(args);
    paging.add(size);
    paging.add((long) page * size);
    var rows =
        q.db.queryForList(
            HistoryQueries.COLLABORATIONS
                + filter
                + " order by c.updated_at desc,c.id desc limit ? offset ?",
            paging.toArray());
    var channels = q.channels(id);
    return q.page(
        rows.stream()
            .map(r -> q.collaboration(r, channels.getOrDefault(q.uuid(r.get("id")), List.of())))
            .toList(),
        page,
        size,
        count);
  }

  public CollaborationVideoStorage.Playback video(UUID campaign, UUID id, UUID deliverable, UUID submission) {
    q.access(false);
    q.owned(campaign);
    q.one("select id from collaboration where id=? and campaign_id=?", id, campaign);
    return videos.playback(id, deliverable, submission);
  }

  public CollaborationDetail collaboration(UUID campaign, UUID id) {
    q.access(false);
    q.owned(campaign);
    var row = q.one(HistoryQueries.COLLABORATIONS + " and c.id=?", campaign, id);
    UUID deal = q.uuid(row.get("deal_id"));
    var events = new ArrayList<DealEvent>();
    Map<String, Object> confirmation = null;
    if (deal != null)
      for (var event : execution.dealEvents(deal)) {
        var metadata = safeMetadata(q.decode(event.get("metadata")));
        events.add(
            new DealEvent(
                q.uuid(event.get("id")),
                event.get("action").toString(),
                q.number(event.get("revision")),
                q.uuid(event.get("actor_id")),
                q.instant(event.get("created_at")),
                metadata));
        if ("DEAL_CONFIRMED".equals(event.get("action"))) confirmation = event;
      }
    Agreement agreement =
        new Agreement(
            null,
            null,
            "UNAVAILABLE",
            "LEGACY_UNVERIFIED",
            null,
            null,
            null,
            q.json.nullNode(),
            q.json.nullNode());
    if (confirmation != null) {
      var proof = safeMetadata(q.decode(confirmation.get("metadata")));
      var rev = proof.path("proposalRevision");
      if (rev.isIntegralNumber() && rev.canConvertToLong()) {
        var proposal =
            q.optional(
                "select * from cooperation_proposal where deal_id=? and revision=?",
                deal,
                rev.longValue());
        if (proposal.isPresent()) {
          var p = proposal.get();
          agreement =
              new Agreement(
                  q.uuid(p.get("id")),
                  q.number(p.get("revision")),
                  "AVAILABLE",
                  "CONFIRMED_PROPOSAL",
                  (java.math.BigDecimal) p.get("fee"),
                  (String) p.get("currency"),
                  (String) p.get("terms"),
                  q.decode(p.get("deliverables")),
                  proof);
        }
      }
    }
    var legacy =
        q.optional(
            "select"
                + " id,views,clicks,likes,comments,shares,kpi_type,kpi_target,kpi_actual,result_note,recorded_at,actual_fee,currency,revenue,roi,evidence_quality,completed_at"
                + " from campaign_outcome where collaboration_id=?",
            id);
    JsonNode legacyView = q.json.nullNode();
    if ("LEGACY".equals(row.get("execution_source")) && legacy.isPresent()) {
      var data = q.json.createObjectNode();
      data.put("provenance", "LEGACY_UNVERIFIED");
      data.set("storedValues", q.json.valueToTree(legacy.get()));
      legacyView = data;
    }
    return new CollaborationDetail(
        q.collaboration(row, q.channels(campaign).getOrDefault(id, List.of())),
        agreement,
        events,
        q.json.valueToTree(execution.snapshot(id)),
        legacyView,
        "CURRENT_PROFILE");
  }

  ObjectNode safeMetadata(JsonNode raw) {
    var out = q.json.createObjectNode();
    for (String key :
        List.of(
            "proposalRevision",
            "agreementSource",
            "agreedAt",
            "evidenceSource",
            "evidenceUrl",
            "evidenceDescription",
            "proposalId",
            "collaborationId",
            "reason",
            "deliveryStatus")) if (raw.has(key)) out.set(key, raw.get(key));
    return out;
  }

  record Selected(SourceKind kind, UUID contextId, JsonNode data, List<String> warnings) {}

  Selected select(UUID id, Map<String, Object> campaign, UUID contextId) {
    var context =
        contextId == null
            ? q.latestContext(id)
            : Optional.of(
                q.one(
                    "select * from campaign_context where campaign_id=? and id=?", id, contextId));
    if (context.isPresent())
      return new Selected(
          SourceKind.CONTEXT_SNAPSHOT,
          q.uuid(context.get().get("id")),
          q.decode(context.get().get("context_data")),
          List.of());
    var entity =
        campaignRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("Campaign not found"));
    SourceKind kind =
        "DRAFT".equals(campaign.get("status"))
            ? SourceKind.CURRENT_DRAFT
            : SourceKind.CURRENT_RECORD_FALLBACK;
    return new Selected(
        kind,
        null,
        CampaignBriefData.snapshot(entity, q.json),
        kind == SourceKind.CURRENT_RECORD_FALLBACK
            ? List.of("HISTORICAL_CONTEXT_MISSING_CURRENT_RECORD_ONLY")
            : List.of("CURRENT_DRAFT_NOT_A_HISTORICAL_SNAPSHOT"));
  }

  public Preview preview(UUID id, UUID contextId) {
    q.access(false);
    var campaign = q.owned(id);
    var selected = select(id, campaign, contextId);
    var draft = HistoryBrief.clean(q.json, selected.data);
    draft.putNull("startDate");
    draft.putNull("endDate");
    var warnings = new ArrayList<>(selected.warnings);
    if (selected.data.hasNonNull("budgetMin") || selected.data.hasNonNull("budgetMax"))
      warnings.add("LEGACY_BUDGET_NOT_CONVERTED");
    if (!HistoryBrief.missing(selected.data).isEmpty()) warnings.add("SOURCE_FIELDS_MISSING");
    return new Preview(
        new CopyOrigin(
            id, selected.contextId, q.number(campaign.get("campaign_revision")), selected.kind),
        draft,
        List.of("startDate", "endDate"),
        List.of(
            "name",
            "timeline",
            "totalBudget",
            "currency",
            "creatorFeeMin",
            "creatorFeeMax",
            "targetCreatorCount",
            "deliverables"),
        warnings);
  }

  @Transactional(readOnly = false, isolation = Isolation.READ_COMMITTED)
  public DuplicateResult duplicate(UUID id, Duplicate request, String key, Authentication auth) {
    q.access(true);
    q.owned(id);
    q.one("select id from \"user\" where id=? for update", q.actor());
    q.one("select id from campaign where id=? for update", id);
    q.access(true);
    var source = q.owned(id);
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
      throw new ValidationException("Idempotency-Key required (1-100 safe characters)");
    var fingerprint = q.json.createObjectNode().put("sourceCampaignId", id.toString());
    fingerprint.set("request", q.json.valueToTree(request));
    String hash = hash(HistoryBrief.canonical(q.json, fingerprint).toString());
    var old =
        q.optional(
            "select request_hash,response from campaign_duplicate_request where actor_id=? and"
                + " request_key=?",
            q.actor(),
            key);
    if (old.isPresent()) {
      if (!hash.equals(old.get().get("request_hash")))
        throw new ConflictException(
            ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency key has different content");
      try {
        return q.json.treeToValue(q.decode(old.get().get("response")), DuplicateResult.class);
      } catch (Exception e) {
        throw new IllegalStateException("Invalid duplicate receipt");
      }
    }
    if (!Objects.equals(
        q.number(source.get("campaign_revision")), request.expectedSourceRevision()))
      throw new ConflictException(
          ErrorCode.CAMPAIGN_REVISION_CONFLICT,
          "Source campaign changed; review the preview again");
    var selected = select(id, source, request.sourceContextId());
    if (selected.kind != request.sourceKind()
        || !Objects.equals(selected.contextId, request.sourceContextId()))
      throw new ConflictException(
          ErrorCode.CAMPAIGN_REVISION_CONFLICT, "Duplicate source no longer matches preview");
    var created = campaigns.create(q.uuid(source.get("brand_profile_id")), request.draft(), auth);
    var origin =
        new CopyOrigin(id, selected.contextId, request.expectedSourceRevision(), selected.kind);
    var response = new DuplicateResult(created, origin);
    audit.record(
        "DUPLICATE_CAMPAIGN_BRIEF",
        "Campaign",
        created.id(),
        null,
        origin,
        "Brand confirmed a new DRAFT; no cooperation data copied");
    em.flush();
    String saved =
        q.db.queryForObject(
            "insert into campaign_duplicate_request values(?,?,?,?,?,?,?,?,?::jsonb,?) returning"
                + " response::text",
            String.class,
            q.actor(),
            key,
            hash,
            id,
            selected.contextId,
            request.expectedSourceRevision(),
            selected.kind.name(),
            created.id(),
            q.json.valueToTree(response).toString(),
            java.sql.Timestamp.from(clock.instant()));
    // Return the same persisted representation on first response and retries, including money
    // scale.
    try {
      return q.json.treeToValue(q.decode(saved), DuplicateResult.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid duplicate receipt");
    }
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
