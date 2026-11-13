package com.influencermatch.backend.creator.catalog;

import static com.influencermatch.backend.creator.catalog.CatalogDtos.*;

import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.creator.portal.CreatorPortalProjection;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CreatorCatalogService {
  final JdbcTemplate db;
  final CreatorPortalProjection projection;
  final com.influencermatch.backend.auth.service.AuthSessionStore sessions;
  static final Set<String> STATES =
      Set.of("VERIFIED", "UNVERIFIED", "LEGACY_UNVERIFIED", "SIMULATED");
  static final Set<String> PLATFORMS = Set.of("FACEBOOK", "INSTAGRAM", "TIKTOK", "YOUTUBE");

  void guard(Permission permission) {
    Permissions.require(permission);
    var actor = Permissions.actor();
    var u =
        db.queryForMap("select role,status,auth_version from \"user\" where id=?", actor.getId());
    if (!Set.of("ADMIN", "DATA_MANAGER").contains(u.get("role"))
        || !"ACTIVE".equals(u.get("status"))
        || ((Number) u.get("auth_version")).longValue() != actor.getAuthVersion())
      throw new ForbiddenException("Active data administrator required");
    if (db.queryForObject(
            "select count(*) from role_permission_override where role=? and permission=? and not"
                + " enabled",
            Long.class,
            u.get("role"),
            permission.name())
        > 0) throw new ForbiddenException("Permission disabled");
    sessions.require(sessions.currentId(), actor, false);
  }

  static void page(int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new ValidationException("Invalid page or size");
  }

  static String choice(String value, Set<String> allowed) {
    if (value != null && !allowed.contains(value)) throw new ValidationException("Invalid filter");
    return value;
  }

  static String search(String q) {
    if (q != null && q.length() > 200) throw new ValidationException("Search is too long");
    return q == null ? null : q.trim();
  }

  static LocalDateTime time(Object o) {
    return o == null ? null : o instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) o;
  }

  <T> PageResponse<T> result(List<T> items, int page, int size, long count) {
    return new PageResponse<>(items, page, size, count, (int) ((count + size - 1) / size));
  }

  public java.util.Map<String, Object> summary() {
    guard(Permission.VIEW_CREATOR_MASTER_CATALOG);

    long totalMasters = Optional.ofNullable(
        db.queryForObject("SELECT COUNT(*) FROM creator_master", Long.class)
    ).orElse(0L);

    long claimedChannels = Optional.ofNullable(
        db.queryForObject("SELECT COUNT(*) FROM creator WHERE master_id IS NOT NULL", Long.class)
    ).orElse(0L);

    long unclaimedChannels = Optional.ofNullable(
        db.queryForObject("SELECT COUNT(*) FROM creator WHERE master_id IS NULL", Long.class)
    ).orElse(0L);

    long duplicateCandidates = 0L;
    try {
      duplicateCandidates = Optional.ofNullable(
          db.queryForObject("SELECT COUNT(*) FROM creator_identity_conflict WHERE status = 'PENDING_MANAGER'", Long.class)
      ).orElse(0L);
    } catch (Exception ignored) {
    }

    java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
    res.put("total_masters", totalMasters);
    res.put("totalMasters", totalMasters);
    res.put("claimed_channels_count", claimedChannels);
    res.put("claimedChannelsCount", claimedChannels);
    res.put("unclaimed_channels_count", unclaimedChannels);
    res.put("unclaimedChannelsCount", unclaimedChannels);
    res.put("duplicate_candidates_count", duplicateCandidates);
    res.put("duplicateCandidatesCount", duplicateCandidates);
    return res;
  }

  public PageResponse<Master> masters(
      String q, String status, String platform, String verification, int page, int size) {
    guard(Permission.VIEW_CREATOR_MASTER_CATALOG);
    page(page, size);
    q = search(q);
    choice(status, Set.of("ACTIVE", "SUSPENDED"));
    choice(platform, PLATFORMS);
    choice(verification, STATES);
    var args = new ArrayList<Object>();
    String where = " where true";
    if (q != null && !q.isEmpty()) {
      where += " and (strpos(lower(m.canonical_name),lower(?))>0 or m.id::text=lower(?))";
      args.add(q);
      args.add(q);
    }
    if (status != null) {
      where += " and m.status=?";
      args.add(status);
    }
    if (platform != null || verification != null) {
      where +=
          " and exists(select 1 from creator c join creator_identity_catalog_state s on"
              + " s.creator_id=c.id where c.master_id=m.id";
      if (platform != null) {
        where += " and c.platform=?";
        args.add(platform);
      }
      if (verification != null) {
        where += " and s.verification_status=?";
        args.add(verification);
      }
      where += ")";
    }
    long count =
        db.queryForObject(
            "select count(*) from creator_master m" + where, Long.class, args.toArray());
    var paged = new ArrayList<>(args);
    paged.add(size);
    paged.add((long) page * size);
    var rows =
        db.queryForList(
            "select m.id,m.user_id,m.canonical_name,m.status,m.created_at from creator_master m"
                + where
                + " order by m.created_at desc,m.id desc limit ? offset ?",
            paged.toArray());
    return result(masterRows(rows), page, size, count);
  }

  List<Master> masterRows(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return List.of();
    var ids = rows.stream().map(r -> r.get("id")).toList();
    var counts = new HashMap<UUID, Map<String, Long>>();
    for (var r :
        db.queryForList(
            "select c.master_id,s.verification_status,count(*) n from creator c join"
                + " creator_identity_catalog_state s on s.creator_id=c.id where c.master_id in ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ") group by c.master_id,s.verification_status",
            ids.toArray()))
      counts
          .computeIfAbsent((UUID) r.get("master_id"), k -> new TreeMap<>())
          .put((String) r.get("verification_status"), ((Number) r.get("n")).longValue());
    return rows.stream()
        .map(
            r -> {
              var c = counts.getOrDefault(r.get("id"), Map.of());
              return new Master(
                  (UUID) r.get("id"),
                  (UUID) r.get("user_id"),
                  (String) r.get("canonical_name"),
                  (String) r.get("status"),
                  time(r.get("created_at")),
                  c.values().stream().mapToLong(Long::longValue).sum(),
                  c);
            })
        .toList();
  }

  public Master master(UUID id) {
    guard(Permission.VIEW_CREATOR_MASTER_CATALOG);
    var r =
        db.queryForList(
            "select id,user_id,canonical_name,status,created_at from creator_master where id=?",
            id);
    if (r.isEmpty()) throw new NotFoundException("Master not found");
    return masterRows(r).getFirst();
  }

  public PageResponse<Account> accounts(
      UUID master, String q, String platform, String source, Boolean stable, int page, int size) {
    guard(Permission.VIEW_CREATOR_MASTER_CATALOG);
    page(page, size);
    q = search(q);
    choice(platform, PLATFORMS);
    choice(source, Set.of("PUBLIC", "AUTHORIZED"));
    if (master != null) master(master);
    var args = new ArrayList<Object>();
    String where = master == null ? " where c.master_id is null" : " where c.master_id=?";
    if (master != null) args.add(master);
    if (q != null && !q.isEmpty()) {
      where +=
          " and (strpos(lower(c.user_name),lower(?))>0 or"
              + " strpos(lower(coalesce(c.display_name,'')),lower(?))>0 or"
              + " strpos(lower(c.external_id),lower(?))>0)";
      args.add(q);
      args.add(q);
      args.add(q);
    }
    if (platform != null) {
      where += " and c.platform=?";
      args.add(platform);
    }
    if (source != null) {
      where += " and c.source_type=?";
      args.add(source);
    }
    if (stable != null) {
      where += " and c.is_stable_id=?";
      args.add(stable);
    }
    long count =
        db.queryForObject("select count(*) from creator c" + where, Long.class, args.toArray());
    args.add(size);
    args.add((long) page * size);
    var rows =
        db.queryForList(
            "select"
                + " c.id,c.master_id,c.platform,c.external_id,c.user_name,c.display_name,c.source_type,c.is_stable_id,c.created_at"
                + " from creator c"
                + where
                + " order by c.created_at desc,c.id desc limit ? offset ?",
            args.toArray());
    var proofs = projection.project(rows.stream().map(r -> (UUID) r.get("id")).toList(), false);
    return result(
        rows.stream()
            .map(
                r ->
                    new Account(
                        (UUID) r.get("id"),
                        (UUID) r.get("master_id"),
                        (String) r.get("platform"),
                        (String) r.get("external_id"),
                        (String) r.get("user_name"),
                        (String) r.get("display_name"),
                        (String) r.get("source_type"),
                        (boolean) r.get("is_stable_id"),
                        time(r.get("created_at")),
                        proofs.get(r.get("id")).verification()))
            .toList(),
        page,
        size,
        count);
  }

  static final String EVENTS =
      """
WITH events AS (
SELECT id,'IDENTITY_AUDIT'::text source,event_type,created_at occurred_at,performed_by actor_id,
  account_id creator_id,master_id,NULL::uuid claim_id,NULL::text status,NULL::text reason_code
FROM creator_identity_audit
UNION ALL
SELECT a.id,'AUDIT_LOG',a.action,a.created_at,a.user_id,
  CASE WHEN a.entity_type='Creator' THEN a.entity_id WHEN a.entity_type='CreatorClaim' THEN cl.creator_id END,
  CASE WHEN a.new_value->>'masterId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN (a.new_value->>'masterId')::uuid END,CASE WHEN a.entity_type='CreatorClaim' THEN a.entity_id END,
  CASE WHEN a.new_value->>'status' IN ('PENDING','APPROVED','REJECTED','REVOKED') THEN a.new_value->>'status' END,
  CASE WHEN a.new_value->>'reason' IN ('CREATOR_ALREADY_LINKED','IDENTITY_MISMATCH','CREATOR_INVITATION_PENDING','INVITATION_ALREADY_PROCESSED','INVITATION_EXPIRED') THEN a.new_value->>'reason' END
FROM audit_log a LEFT JOIN creator_claim cl ON a.entity_type='CreatorClaim' AND cl.id=a.entity_id
WHERE a.action IN ('SUBMIT_CREATOR_CLAIM','REVIEW_CREATOR_CLAIM','REJECT_CREATOR_CLAIM','REVOKE_CREATOR_CLAIM','REJECT_CREATOR_INVITATION_OR_OWNERSHIP')
)
""";

  public PageResponse<Event> events(
      UUID master,
      UUID creator,
      UUID claim,
      UUID actor,
      String type,
      LocalDateTime from,
      LocalDateTime to,
      int page,
      int size) {
    guard(Permission.VIEW_CREATOR_IDENTITY_HISTORY);
    page(page, size);
    if (from != null && to != null && from.isAfter(to))
      throw new ValidationException("Invalid date range");
    if (type != null
        && !Set.of(
                "CLAIM_MASTER",
                "LINK_ACCOUNT",
                "LINK_NEW_ACCOUNT",
                "REFRESH_ACCOUNT_TOKEN",
                "UNLINK_ACCOUNT",
                "REVOKE_ACCESS",
                "CONFLICT_RESOLVED_TRANSFER",
                "CONFLICT_REJECTED",
                "SUBMIT_CREATOR_CLAIM",
                "REVIEW_CREATOR_CLAIM",
                "REJECT_CREATOR_CLAIM",
                "REVOKE_CREATOR_CLAIM",
                "REJECT_CREATOR_INVITATION_OR_OWNERSHIP",
                "LINK_META_ACCOUNT",
                "REFRESH_META_VERIFICATION",
                "LINK_TIKTOK_ACCOUNT",
                "REFRESH_TIKTOK_VERIFICATION",
                "META_OAUTH_VERIFIED",
                "TIKTOK_OAUTH_VERIFIED")
            .contains(type)) throw new ValidationException("Invalid event type");
    String where = " where true";
    var args = new ArrayList<Object>();
    var filters = new LinkedHashMap<String, Object>();
    filters.put("master_id", master);
    filters.put("creator_id", creator);
    filters.put("claim_id", claim);
    filters.put("actor_id", actor);
    filters.put("event_type", type);
    for (var e : filters.entrySet())
      if (e.getValue() != null) {
        where += " and " + e.getKey() + "=?";
        args.add(e.getValue());
      }
    if (from != null) {
      where += " and occurred_at>=?";
      args.add(from);
    }
    if (to != null) {
      where += " and occurred_at<=?";
      args.add(to);
    }
    long count =
        db.queryForObject(
            EVENTS + "select count(*) from events" + where, Long.class, args.toArray());
    args.add(size);
    args.add((long) page * size);
    var rows =
        db.queryForList(
            EVENTS
                + "select * from events"
                + where
                + " order by occurred_at desc,source desc,id desc limit ? offset ?",
            args.toArray());
    return result(
        rows.stream()
            .map(
                r ->
                    new Event(
                        (UUID) r.get("id"),
                        (String) r.get("source"),
                        (String) r.get("event_type"),
                        time(r.get("occurred_at")),
                        (UUID) r.get("actor_id"),
                        (UUID) r.get("creator_id"),
                        (UUID) r.get("master_id"),
                        (UUID) r.get("claim_id"),
                        (String) r.get("status"),
                        (String) r.get("reason_code")))
            .toList(),
        page,
        size,
        count);
  }
}
