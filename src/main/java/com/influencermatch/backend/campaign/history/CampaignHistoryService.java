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
import com.influencermatch.backend.exception.*;
import jakarta.persistence.EntityManager;
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
        q.origin(id));
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
