package com.influencermatch.backend.recommendation.engine.memory;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Temporal review selection shared by the live scorer and PostgreSQL contract tests. */
public final class M4ReviewHistory {
  public static final int WINDOW_MONTHS = 24;
  public static final String POLICY = "m4-review-and-completion-asof-24m-exclude-current-v1";
  private M4ReviewHistory() {}

  public static List<LearnedM4Evaluator.BrandReviewRecord> select(
      JdbcTemplate jdbc, UUID brand, UUID currentCampaign, LocalDateTime scoringAsOf) {
    Objects.requireNonNull(scoringAsOf, "scoringAsOf");
    Objects.requireNonNull(brand, "brand");
    Objects.requireNonNull(currentCampaign, "currentCampaign");
    var cutoff = Timestamp.valueOf(scoringAsOf.minusMonths(WINDOW_MONTHS));
    var asOf = Timestamp.valueOf(scoringAsOf);
    return jdbc.query("""
        select r.id as review_id, ca.brand_profile_id, c.id as creator_id, c.master_id, c.platform,
               coalesce((select m.niche from public_creator_metric m
                 where m.creator_id=c.id and m.collected_at<=r.reviewed_at
                 order by m.collected_at desc,m.id desc limit 1),'') as niche,
               r.brand_fit_rating
        from creator_review r
        join collaboration l on l.id=r.collaboration_id
        join campaign ca on ca.id=l.campaign_id
        join brand_profile b on b.id=ca.brand_profile_id
        join creator c on c.id=l.creator_id
        where ca.brand_profile_id=? and ca.id<>? and l.status='COMPLETED'
          and r.reviewed_by_user_id=b.user_id and r.brand_fit_rating between 1 and 5
          and r.reviewed_at>=? and r.reviewed_at<=?
          and l.completed_at>=? and l.completed_at<=?
        order by r.reviewed_at,r.id
        """,
        (rs, row) -> new LearnedM4Evaluator.BrandReviewRecord(
            rs.getObject("review_id", UUID.class), rs.getObject("brand_profile_id", UUID.class),
            rs.getObject("creator_id", UUID.class), rs.getObject("master_id", UUID.class),
            rs.getString("platform"), rs.getString("niche"), rs.getInt("brand_fit_rating")),
        brand, currentCampaign, cutoff, asOf, cutoff, asOf);
  }
}
