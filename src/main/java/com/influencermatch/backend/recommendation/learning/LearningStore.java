package com.influencermatch.backend.recommendation.learning;

import com.fasterxml.jackson.databind.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal batch reader/writer; never exposes other Brands' evidence through public DTOs. */
@Service
@RequiredArgsConstructor
public class LearningStore {
  final JdbcTemplate db;
  final ObjectMapper json;
  final Clock clock;

  @Transactional(propagation = Propagation.MANDATORY)
  public LearningPolicy.Snapshot snapshot(
      UUID brand, Collection<UUID> masters, Collection<UUID> creators) {
    Instant now = clock.instant();
    List<Object> args =
        new ArrayList<>(
            List.of(Timestamp.from(LearningPolicy.cutoff(now)), Timestamp.from(now), brand));
    String predicate = "ca.brand_profile_id=?";
    if (!masters.isEmpty()) {
      predicate += " or h.master_id in (" + marks(masters.size()) + ")";
      args.addAll(masters);
    }
    if (!creators.isEmpty()) {
      predicate += " or h.creator_id in (" + marks(creators.size()) + ")";
      args.addAll(creators);
    }
    var rows =
        db.queryForList(
            """
select h.id,h.source_review_id,h.collaboration_id,h.creator_id,h.master_id,h.learning_snapshot,h.evidence_value,l.master_id agreement_master,r.accepted_outcome_revision,a.id acceptance_id,
  ca.brand_profile_id,r.overall_rating,r.brand_fit_rating,r.reviewed_at
from historical_campaign h join creator_review r on r.id=h.source_review_id and r.collaboration_id=h.collaboration_id
join collaboration l on l.id=h.collaboration_id and l.status='COMPLETED'
join campaign ca on ca.id=l.campaign_id join brand_profile b on b.id=ca.brand_profile_id and b.user_id=r.reviewed_by_user_id
join collaboration_outcome_acceptance a on a.collaboration_id=l.id and a.outcome_revision=r.accepted_outcome_revision and a.reopened_at is null
where h.source_revision=1 and h.provenance='BRAND_ACCEPTED'
and r.reviewed_at>=? and r.reviewed_at<=? and (
"""
                + predicate
                + ") order by r.reviewed_at,r.id",
            args.toArray());
    var evidence = new ArrayList<LearningPolicy.Evidence>();
    for (var r : rows) {
      boolean compatibility = r.get("learning_snapshot") == null;
      Object source = compatibility ? r.get("evidence_value") : r.get("learning_snapshot");
      if (source == null) continue;
      var p = decode(source);
      UUID master = (UUID) r.get("master_id");
      if (compatibility) {
        // V29 already saved accepted review facts. Re-use those facts only; never invent
        // a historical platform/niche, KPI, deadline or current-owner mapping.
        if (!p.path("reviewerAuthenticated").asBoolean()
            || !p.path("brandAccepted").asBoolean()
            || !"SELF_REPORTED".equals(p.path("metricSource").asText())
            || p.path("outcomeRevision").asLong(-1)
                != ((Number) r.get("accepted_outcome_revision")).longValue()
            || !Objects.equals(master, r.get("agreement_master"))) continue;
      } else {
        if (p.path("schemaVersion").asInt() != 1
            || !r.get("acceptance_id").toString().equals(p.path("acceptanceId").asText())
            || !r.get("source_review_id").toString().equals(p.path("reviewId").asText())
            || p.path("outcomeRevision").asLong(-1)
                != ((Number) r.get("accepted_outcome_revision")).longValue()
            || !Objects.equals(
                master == null ? null : master.toString(),
                p.path("agreement").path("masterId").asText(null))) continue;
      }
      var agreement = p.path("agreement");
      evidence.add(
          new LearningPolicy.Evidence(
              (UUID) r.get("id"),
              (UUID) r.get("source_review_id"),
              (UUID) r.get("collaboration_id"),
              (UUID) r.get("brand_profile_id"),
              master,
              (UUID) r.get("creator_id"),
              ((Timestamp) r.get("reviewed_at")).toInstant(),
              compatibility ? null : agreement.path("objective").asText(null),
              compatibility ? null : p.path("platform").asText(null),
              compatibility ? null : p.path("niche").asText(null),
              p.path("overallRating").asInt(),
              p.path("brandFitRating").asInt(),
              !compatibility && p.path("onTime").isBoolean() ? p.path("onTime").asBoolean() : null,
              !compatibility && p.path("kpi").isNumber() ? p.path("kpi").asDouble() : null));
    }
    var snapshot = LearningPolicy.snapshot(now, evidence);
    saveCohorts(brand, snapshot);
    return snapshot;
  }

  private static String marks(int n) {
    return String.join(",", Collections.nCopies(n, "?"));
  }

  private JsonNode decode(Object value) {
    try {
      return json.readTree(value.toString());
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored learning snapshot", e);
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid learning data", e);
    }
  }

  private void saveCohorts(UUID brand, LearningPolicy.Snapshot snapshot) {
    db.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "brand:m4:" + brand);
    var cohorts = snapshot.cohorts(brand);
    var keys =
        cohorts.stream()
            .map(LearningPolicy.Cohort::key)
            .collect(java.util.stream.Collectors.toSet());
    for (var row :
        db.queryForList(
            "select id,cohort_key from brand_pattern where brand_profile_id=? and pattern_type=?",
            brand,
            LearningPolicy.M4_TYPE))
      if (!keys.contains(row.get("cohort_key")))
        db.update("delete from brand_pattern where id=?", row.get("id"));
    for (var c : cohorts)
      db.update(
          """
insert into brand_pattern(id,brand_profile_id,pattern_type,platform,niche,cohort_key,pattern_value,confidence,evidence_count,learned_at)
values(?,?,?,?,?,?,?::jsonb,?,?,?) on conflict(brand_profile_id,pattern_type,cohort_key)
where pattern_type='BRAND_FIT_BAYES_V2' do update set pattern_value=excluded.pattern_value,
confidence=excluded.confidence,evidence_count=excluded.evidence_count,learned_at=excluded.learned_at
where brand_pattern.pattern_value is distinct from excluded.pattern_value
""",
          UUID.randomUUID(),
          brand,
          LearningPolicy.M4_TYPE,
          c.platform() == null ? "" : c.platform(),
          c.niche() == null ? "" : c.niche(),
          c.key(),
          encode(c),
          c.support(),
          c.count(),
          Timestamp.from(snapshot.asOf()));
  }

  public void trace(UUID run, UUID creator, LearningPolicy.M1 m1, LearningPolicy.Cohort m4) {
    references(run, creator, "M1_R", m1.reviewEvidence());
    references(run, creator, "M1_T", m1.timeEvidence());
    references(run, creator, "M1_K", m1.kpiEvidence());
    if (m4 != null) references(run, creator, "M4", m4.evidenceIds());
  }

  private void references(UUID run, UUID creator, String component, List<UUID> ids) {
    for (UUID id : ids)
      db.update(
          "insert into recommendation_scoring_evidence(run_id,creator_id,evidence_id,component)"
              + " values(?,?,?,?) on conflict do nothing",
          run,
          creator,
          id,
          component);
  }
}
