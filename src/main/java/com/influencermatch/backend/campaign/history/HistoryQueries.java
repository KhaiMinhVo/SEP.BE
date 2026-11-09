package com.influencermatch.backend.campaign.history;

import static com.influencermatch.backend.campaign.history.HistoryDtos.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HistoryQueries {
  final JdbcTemplate db;
  final ObjectMapper json;

  UUID actor() {
    return Permissions.actor().getId();
  }

  void access(boolean create) {
    Permissions.require(Permission.VIEW_OWN_CAMPAIGN);
    Permissions.require(Permission.VIEW_OWN_HISTORY_AND_INSIGHTS);
    if (create) Permissions.require(Permission.CREATE_CAMPAIGN);
    var u = one("select role,status,email_verified,auth_version from \"user\" where id=?", actor());
    if (!"BRAND".equals(u.get("role"))
        || !"ACTIVE".equals(u.get("status"))
        || !Boolean.TRUE.equals(u.get("email_verified"))
        || ((Number) u.get("auth_version")).longValue() != Permissions.actor().getAuthVersion())
      throw new ForbiddenException("Active Brand owner required");
    var permissions =
        new ArrayList<>(
            List.of(
                Permission.VIEW_OWN_CAMPAIGN.name(),
                Permission.VIEW_OWN_HISTORY_AND_INSIGHTS.name()));
    if (create) permissions.add(Permission.CREATE_CAMPAIGN.name());
    for (var p : permissions)
      if (db.queryForObject(
              "select count(*) from role_permission_override where role='BRAND' and permission=?"
                  + " and not enabled",
              Long.class,
              p)
          > 0) throw new ForbiddenException("History permission disabled");
  }

  Map<String, Object> owned(UUID id) {
    return one(
        "select c.* from campaign c join brand_profile b on b.id=c.brand_profile_id where c.id=?"
            + " and b.user_id=?",
        id,
        actor());
  }

  Map<String, Object> one(String sql, Object... args) {
    return optional(sql, args)
        .orElseThrow(() -> new NotFoundException("Campaign history resource not found"));
  }

  Optional<Map<String, Object>> optional(String sql, Object... args) {
    return db.queryForList(sql, args).stream().findFirst();
  }

  JsonNode decode(Object value) {
    try {
      return value == null ? json.nullNode() : json.readTree(value.toString());
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored history data");
    }
  }

  UUID uuid(Object value) {
    return (UUID) value;
  }

  Long number(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  LocalDateTime local(Object value) {
    return value == null
        ? null
        : value instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) value;
  }

  Instant instant(Object value) {
    return value == null
        ? null
        : value instanceof Timestamp t
            ? t.toInstant()
            : value instanceof OffsetDateTime o ? o.toInstant() : (Instant) value;
  }

  List<String> strings(Object value) {
    try {
      return value == null
          ? List.of()
          : Arrays.asList((String[]) ((java.sql.Array) value).getArray());
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored platforms");
    }
  }

  static void page(int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new ValidationException("page must be nonnegative; size must be 1-100");
  }

  <T> PageResponse<T> page(List<T> items, int page, int size, long count) {
    return new PageResponse<>(items, page, size, count, (int) ((count + size - 1) / size));
  }

  Context context(Map<String, Object> row) {
    var data = decode(row.get("context_data"));
    return new Context(
        uuid(row.get("id")),
        ((Number) row.get("context_version")).intValue(),
        uuid(row.get("brand_context_id")),
        Boolean.TRUE.equals(row.get("active")),
        local(row.get("created_at")),
        data.path("snapshotKind").asText("LEGACY_UNVERIFIED"),
        HistoryBrief.clean(json, data),
        HistoryBrief.missing(data));
  }

  Optional<Map<String, Object>> latestContext(UUID id) {
    return optional(
        "select * from campaign_context where campaign_id=? order by context_version desc,id desc"
            + " limit 1",
        id);
  }

  CopyOrigin origin(UUID target) {
    return optional(
            "select source_campaign_id,source_context_id,source_revision,source_kind from"
                + " campaign_duplicate_request where target_campaign_id=?",
            target)
        .map(
            r ->
                new CopyOrigin(
                    uuid(r.get("source_campaign_id")),
                    uuid(r.get("source_context_id")),
                    number(r.get("source_revision")),
                    SourceKind.valueOf(r.get("source_kind").toString())))
        .orElse(null);
  }

  Map<UUID, List<Channel>> channels(UUID campaign) {
    var out = new HashMap<UUID, List<Channel>>();
    for (var r :
        db.queryForList(
            "select cc.collaboration_id,c.id,c.master_id,c.platform,c.user_name,c.display_name from"
                + " collaboration_channel cc join collaboration l on l.id=cc.collaboration_id join"
                + " creator c on c.id=cc.creator_id where l.campaign_id=? order by c.id",
            campaign))
      out.computeIfAbsent(uuid(r.get("collaboration_id")), k -> new ArrayList<>())
          .add(
              new Channel(
                  uuid(r.get("id")),
                  uuid(r.get("master_id")),
                  (String) r.get("platform"),
                  (String) r.get("user_name"),
                  (String) r.get("display_name")));
    return out;
  }

  CollaborationItem collaboration(Map<String, Object> r, List<Channel> channels) {
    return new CollaborationItem(
        uuid(r.get("id")),
        uuid(r.get("deal_id")),
        CollaborationStatus.valueOf(r.get("status").toString()),
        uuid(r.get("master_id")),
        (BigDecimal) r.get("agreed_fee"),
        (String) r.get("currency"),
        (String) r.get("execution_source"),
        local(r.get("start_at")),
        local(r.get("completed_at")),
        local(r.get("updated_at")),
        number(r.get("accepted_outcome_revision")),
        "LEGACY".equals(r.get("execution_source")) ? "LEGACY_UNVERIFIED" : "SELF_REPORTED",
        "CURRENT_PROFILE",
        channels);
  }

  static final String COLLABORATIONS =
      "select c.*,d.id deal_id,r.accepted_outcome_revision from collaboration c left join"
          + " cooperation_deal d on d.collaboration_id=c.id left join creator_review r on"
          + " r.collaboration_id=c.id where c.campaign_id=?";

  Report report(UUID campaign) {
    var publications = new HashMap<UUID, Map<String, String>>();
    for (var p :
        db.queryForList(
            "select d.collaboration_id,p.deliverable_id,p.submission_revision,p.post_key from"
                + " collaboration_publication p join collaboration_deliverable d on"
                + " d.id=p.deliverable_id join collaboration c on c.id=d.collaboration_id where"
                + " c.campaign_id=?",
            campaign))
      publications
          .computeIfAbsent(uuid(p.get("collaboration_id")), k -> new HashMap<>())
          .put(
              p.get("deliverable_id") + ":" + p.get("submission_revision"),
              p.get("post_key").toString());
    var sources = new ArrayList<CampaignHistoryCalculator.Source>();
    for (var r :
        db.queryForList(
            """
select c.id,c.status,c.agreed_fee,c.currency,c.execution_source,o.payload,o.calculation,
 case when r.accepted_outcome_revision is not null and a.id is not null and o.id is not null
 and r.reviewed_by_user_id=b.user_id and a.actor_id=b.user_id and compat.id is not null
 and o.source='SELF_REPORTED' and c.execution_source<>'LEGACY' then true else false end eligible
from collaboration c join campaign ca on ca.id=c.campaign_id join brand_profile b on b.id=ca.brand_profile_id
left join creator_review r on r.collaboration_id=c.id
left join campaign_outcome compat on compat.id=r.outcome_id and compat.collaboration_id=c.id
left join collaboration_outcome_revision o on o.collaboration_id=c.id and o.revision=r.accepted_outcome_revision
left join collaboration_outcome_acceptance a on a.collaboration_id=c.id and a.outcome_revision=r.accepted_outcome_revision and a.reopened_at is null
where c.campaign_id=? order by c.id
""",
            campaign))
      sources.add(
          new CampaignHistoryCalculator.Source(
              uuid(r.get("id")),
              r.get("status").toString(),
              (BigDecimal) r.get("agreed_fee"),
              (String) r.get("currency"),
              Boolean.TRUE.equals(r.get("eligible")),
              decode(r.get("payload")),
              decode(r.get("calculation")),
              publications.getOrDefault(uuid(r.get("id")), Map.of())));
    return CampaignHistoryCalculator.calculate(sources);
  }
}
