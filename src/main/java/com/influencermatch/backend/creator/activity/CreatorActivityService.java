package com.influencermatch.backend.creator.activity;

import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.security.permission.Permissions;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@PreAuthorize("hasRole('DATA_MANAGER')")
public class CreatorActivityService {
  public enum Status { ACTIVE, INACTIVE, UNKNOWN }

  private final JdbcTemplate db;
  private final int warningDays;
  private final int maxDataAgeDays;

  public CreatorActivityService(JdbcTemplate db,
      @Value("${creator-activity.warning-days:30}") int warningDays,
      @Value("${creator-activity.max-data-age-days:7}") int maxDataAgeDays) {
    if (warningDays < 1 || maxDataAgeDays < 1)
      throw new IllegalArgumentException("Creator activity thresholds must be positive");
    this.db = db;
    this.warningDays = warningDays;
    this.maxDataAgeDays = maxDataAgeDays;
  }

  // Latest metric must be selected before evaluating freshness. Failed refreshes cannot
  // turn stale post evidence into a confirmed inactivity alert.
  static final String ASSESSMENT = """
      WITH evidence AS (
        SELECT c.id AS "creatorId", c.user_name AS "userName",
               c.display_name AS "displayName", c.platform, c.profile_url AS "profileUrl",
               c.discovery_hidden AS hidden, p.last_post_at AS "lastPostedAt",
               COALESCE(m.observed_at, m.collected_at) AS "lastObservedAt",
               m.freshness_status AS "freshnessStatus",
               floor(extract(epoch FROM ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - p.last_post_at)) / 86400)::bigint AS "daysSinceLastPost",
               CASE
                 WHEN p.last_post_at IS NULL OR p.invalid_date
                   OR p.last_post_at > (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                   OR m.freshness_status IS DISTINCT FROM 'FRESH'
                   OR COALESCE(m.observed_at, m.collected_at) IS NULL
                   OR COALESCE(m.observed_at, m.collected_at) > (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                   OR COALESCE(m.observed_at, m.collected_at) < (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - (? * interval '1 day')
                 THEN 'UNKNOWN'
                 WHEN p.last_post_at <= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - (? * interval '1 day')
                 THEN 'INACTIVE' ELSE 'ACTIVE' END AS status
        FROM creator c
        LEFT JOIN LATERAL (
          SELECT max(im_post_timestamp(posted_at)) AS last_post_at,
                 bool_or(im_post_timestamp(posted_at) IS NULL) AS invalid_date
          FROM post WHERE creator_id = c.id
        ) p ON true
        LEFT JOIN LATERAL (
          SELECT observed_at, collected_at, freshness_status FROM public_creator_metric
          WHERE creator_id = c.id ORDER BY collected_at DESC, id DESC LIMIT 1
        ) m ON true
      )
      """;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResponse<Map<String, Object>> list(int page, int size, Status status, Boolean hidden) {
    if (page < 0 || size < 1 || size > 100) throw new ValidationException("Invalid page or size (1..100)");
    var args = new ArrayList<Object>(List.of(maxDataAgeDays, warningDays));
    String filter = " WHERE true";
    if (status != null) { filter += " AND status = ?"; args.add(status.name()); }
    if (hidden != null) { filter += " AND hidden = ?"; args.add(hidden); }
    long count = db.queryForObject(ASSESSMENT + "SELECT count(*) FROM evidence" + filter,
        Long.class, args.toArray());
    args.add(size);
    args.add((long) page * size);
    var items = db.queryForList(ASSESSMENT + "SELECT * FROM evidence" + filter
        + " ORDER BY CASE status WHEN 'INACTIVE' THEN 0 WHEN 'UNKNOWN' THEN 1 ELSE 2 END,"
        + " \"lastPostedAt\" ASC NULLS FIRST, \"creatorId\" LIMIT ? OFFSET ?", args.toArray());
    return new PageResponse<>(items, page, size, count, (int) ((count + size - 1) / size));
  }

  @Transactional
  public void setVisibility(UUID creatorId, boolean hidden, String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new ValidationException("A reason of 1..500 characters is required");
    var rows = db.queryForList("SELECT discovery_hidden FROM creator WHERE id=? FOR UPDATE", creatorId);
    if (rows.isEmpty()) throw new ResourceNotFoundException("Creator", "id", creatorId);
    if (Objects.equals(rows.get(0).get("discovery_hidden"), hidden)) return;
    db.update("UPDATE creator SET discovery_hidden=? WHERE id=?", hidden, creatorId);
    db.update("INSERT INTO creator_visibility_audit(id,creator_id,actor_id,hidden,reason) VALUES (?,?,?,?,?)",
        UUID.randomUUID(), creatorId, Permissions.actor().getId(), hidden, reason.trim());
  }
}
