package com.influencermatch.backend.brand.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Rebuilds auditable cohort aggregates from authoritative, current review rows. */
@Service
@RequiredArgsConstructor
public class BrandLearnedPatterns {
  public static final String POLICY = "m4-evidence-v1";
  public static final String TYPE = "BRAND_FIT_BY_PLATFORM_NICHE";
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Clock clock;

  public record Evidence(UUID reviewId, String platform, String niche, int rating) {}

  public record Cohort(
      String platform, String niche, double score, double confidence, List<UUID> reviewIds) {
    public boolean available() {
      return reviewIds.size() >= 5 && confidence >= 0.75;
    }
  }

  public record Snapshot(List<Cohort> cohorts) {
    public Cohort observed(String platform, String niche) {
      String p = normalize(platform), n = normalize(niche);
      return cohorts.stream()
          .filter(c -> c.platform.equals(p) && c.niche.equals(n))
          .findFirst()
          .orElseGet(
              () ->
                  cohorts.stream()
                      .filter(c -> c.platform.equals(p) && c.niche.isEmpty())
                      .findFirst()
                      .orElse(null));
    }

    public Cohort select(String platform, String niche) {
      String p = normalize(platform), n = normalize(niche);
      var exact =
          cohorts.stream()
              .filter(
                  c -> c.platform.equals(p) && !n.isEmpty() && c.niche.equals(n) && c.available())
              .findFirst();
      return exact.orElseGet(
          () ->
              cohorts.stream()
                  .filter(c -> c.platform.equals(p) && c.niche.isEmpty() && c.available())
                  .findFirst()
                  .orElse(null));
    }
  }

  public static String normalize(String value) {
    return value == null
        ? ""
        : java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
  }

  public static Snapshot aggregate(List<Evidence> input) {
    Map<String, List<Evidence>> groups = new TreeMap<>();
    Set<UUID> seen = new HashSet<>();
    input.stream()
        .sorted(Comparator.comparing(Evidence::reviewId))
        .forEach(
            e -> {
              if (!seen.add(e.reviewId)
                  || e.rating < 1
                  || e.rating > 5
                  || normalize(e.platform).isEmpty()) return;
              String p = normalize(e.platform), n = normalize(e.niche);
              groups.computeIfAbsent(p + "\u0001", k -> new ArrayList<>()).add(e);
              if (!n.isEmpty())
                groups.computeIfAbsent(p + "\u0001" + n, k -> new ArrayList<>()).add(e);
            });
    List<Cohort> result = new ArrayList<>();
    groups.forEach(
        (key, rows) -> {
          int split = key.indexOf('\u0001');
          double mean = rows.stream().mapToInt(Evidence::rating).average().orElseThrow();
          result.add(
              new Cohort(
                  key.substring(0, split),
                  key.substring(split + 1),
                  Math.max(0, Math.min(100, 25 * (mean - 1))),
                  Math.min(1, 0.5 + 0.05 * rows.size()),
                  rows.stream().map(Evidence::reviewId).sorted().toList()));
        });
    return new Snapshot(List.copyOf(result));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Snapshot snapshot(UUID brand) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "brand:m4:" + brand);
    var evidence =
        jdbc.query(
            """
select r.id,c.platform,coalesce((select m.niche from public_creator_metric m where m.creator_id=c.id and m.collected_at<=r.reviewed_at order by m.collected_at desc,m.id desc limit 1),'') as niche,r.brand_fit_rating
from creator_review r join collaboration l on l.id=r.collaboration_id
join campaign ca on ca.id=l.campaign_id join brand_profile bp on bp.id=ca.brand_profile_id and bp.user_id=r.reviewed_by_user_id join creator c on c.id=l.creator_id
join campaign_outcome o on o.id=r.outcome_id and o.collaboration_id=l.id
where ca.brand_profile_id=? and l.status='COMPLETED' order by r.id
""",
            (r, n) ->
                new Evidence(
                    r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getInt(4)),
            brand);
    var result = aggregate(evidence);
    // Only this derived type is rebuilt. Legacy/feedback patterns are never altered.
    var keys =
        result.cohorts.stream()
            .map(c -> c.platform + "\u0001" + c.niche)
            .collect(java.util.stream.Collectors.toSet());
    for (var row :
        jdbc.queryForList(
            "select id,platform,niche from brand_pattern where brand_profile_id=? and"
                + " pattern_type=?",
            brand,
            TYPE))
      if (!keys.contains(row.get("platform") + "\u0001" + row.get("niche")))
        jdbc.update("delete from brand_pattern where id=?", row.get("id"));
    for (var c : result.cohorts) {
      var value =
          mapper.createObjectNode().put("scoringPolicyVersion", POLICY).put("score", c.score);
      value.set("reviewIds", mapper.valueToTree(c.reviewIds));
      jdbc.update(
          "insert into"
              + " brand_pattern(id,brand_profile_id,platform,niche,pattern_type,pattern_value,confidence,evidence_count,learned_at)"
              + " values(?,?,?,?,?,cast(? as jsonb),?,?,?) on"
              + " conflict(brand_profile_id,platform,niche) where"
              + " pattern_type='BRAND_FIT_BY_PLATFORM_NICHE' do update set"
              + " pattern_value=excluded.pattern_value,confidence=excluded.confidence,evidence_count=excluded.evidence_count,learned_at=excluded.learned_at"
              + " where brand_pattern.pattern_value is distinct from excluded.pattern_value or"
              + " brand_pattern.confidence is distinct from excluded.confidence or"
              + " brand_pattern.evidence_count is distinct from excluded.evidence_count",
          UUID.randomUUID(),
          brand,
          c.platform,
          c.niche,
          TYPE,
          value.toString(),
          c.confidence,
          c.reviewIds.size(),
          Timestamp.from(clock.instant()));
    }
    return result;
  }
}
