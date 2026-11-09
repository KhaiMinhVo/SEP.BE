package com.influencermatch.backend.cooperation;

import com.influencermatch.backend.recommendation.learning.*;
import java.sql.Timestamp;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Called in confirmation/final review transactions; all source values are read from saved rows. */
@Component
@RequiredArgsConstructor
public class LearningEvidenceWriter {
  private final DealStore s;

  public void agreement(UUID collaboration, UUID proposal) {
    var c =
        s.one(
            "select l.master_id,ca.objective from collaboration l join campaign ca on"
                + " ca.id=l.campaign_id where l.id=?",
            collaboration);
    var p = s.one("select revision,kpi_targets from cooperation_proposal where id=?", proposal);
    var members =
        s.db.queryForList(
            """
select cc.creator_id,c.platform,m.niche from collaboration_channel cc join creator c on c.id=cc.creator_id
left join lateral (select niche from public_creator_metric where creator_id=c.id and collected_at<=? order by collected_at desc,id desc limit 1) m on true
where cc.collaboration_id=? order by cc.creator_id
""",
            s.time(),
            collaboration);
    var targets = s.decode(p.get("kpi_targets"));
    List<LearningKpis.Target> parsed = new ArrayList<>();
    try {
      for (var t : targets) parsed.add(s.json.treeToValue(t, LearningKpis.Target.class));
    } catch (Exception e) {
      throw new com.influencermatch.backend.exception.ValidationException(
          "Invalid stored KPI targets");
    }
    LearningKpis.validate(
        parsed,
        members.stream()
            .map(m -> (UUID) m.get("creator_id"))
            .collect(java.util.stream.Collectors.toSet()),
        s.now());
    var context =
        s.json
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("proposalId", proposal.toString())
            .put("proposalRevision", ((Number) p.get("revision")).longValue());
    context.set("masterId", s.json.valueToTree(c.get("master_id")));
    context.set("objective", s.json.valueToTree(c.get("objective")));
    context.set("members", s.json.valueToTree(members));
    context.set("kpiTargets", targets);
    context.set(
        "deliverables",
        s.json.valueToTree(
            s.db.queryForList(
                "select id,creator_id,due_at from collaboration_deliverable where"
                    + " collaboration_id=? order by id",
                collaboration)));
    s.db.update(
        "update collaboration set learning_context=?::jsonb where id=?",
        context.toString(),
        collaboration);
  }

  public void review(UUID collaboration, UUID review) {
    var c =
        s.one(
            "select l.learning_context,l.master_id,l.creator_id,ca.brand_profile_id from"
                + " collaboration l join campaign ca on ca.id=l.campaign_id where l.id=?",
            collaboration);
    var context = s.decode(c.get("learning_context"));
    if (context.isNull()) return; // Older agreements have no reconstructed classification/targets.
    var r =
        s.one(
            """
select r.*,o.payload,a.id acceptance_id from creator_review r
join collaboration_outcome_revision o on o.collaboration_id=r.collaboration_id and o.revision=r.accepted_outcome_revision
join collaboration_outcome_acceptance a on a.collaboration_id=r.collaboration_id and a.outcome_revision=r.accepted_outcome_revision and a.reopened_at is null
join collaboration l on l.id=r.collaboration_id and l.status='COMPLETED'
join campaign ca on ca.id=l.campaign_id join brand_profile b on b.id=ca.brand_profile_id and b.user_id=r.reviewed_by_user_id
where r.id=? and r.collaboration_id=?
""",
            review,
            collaboration);
    var units =
        s.db.queryForList(
            """
select d.id,d.creator_id,d.due_at,d.submission_revision,sub.source,sub.created_at submitted_at,
sub.id submission_id,p.id publication_id,p.post_key,cr.id content_review_id
from collaboration_deliverable d
left join collaboration_submission sub on sub.deliverable_id=d.id and sub.revision=d.submission_revision
left join collaboration_content_review cr on cr.deliverable_id=d.id and cr.submission_revision=d.submission_revision and cr.decision='APPROVE'
left join collaboration_publication p on p.deliverable_id=d.id and p.submission_revision=d.submission_revision
where d.collaboration_id=? order by d.id
""",
            collaboration);
    boolean known =
        !units.isEmpty()
            && units.stream()
                .allMatch(
                    u ->
                        "CREATOR_PORTAL".equals(u.get("source"))
                            && u.get("content_review_id") != null);
    Boolean onTime =
        known
            ? units.stream()
                .allMatch(
                    u ->
                        !((Timestamp) u.get("submitted_at"))
                            .toInstant()
                            .isAfter(((Timestamp) u.get("due_at")).toInstant()))
            : null;
    var pubs =
        units.stream()
            .filter(u -> u.get("post_key") != null)
            .map(
                u ->
                    new LearningKpis.Publication(
                        (UUID) u.get("id"),
                        (UUID) u.get("creator_id"),
                        ((Number) u.get("submission_revision")).longValue(),
                        u.get("post_key").toString()))
            .toList();
    var outcome = s.decode(r.get("payload"));
    Double kpi =
        pubs.size() == units.size()
            ? LearningKpis.attainment(
                context.path("kpiTargets"), outcome.path("posts"), pubs, s.now())
            : null;
    Set<String> platforms = new HashSet<>(), niches = new HashSet<>();
    boolean allNiches = true;
    for (var member : context.path("members")) {
      String platform = LearningPolicy.normalize(member.path("platform").asText(null));
      String niche = LearningPolicy.normalize(member.path("niche").asText(null));
      if (platform.isEmpty()) platforms.add("");
      else platforms.add(platform);
      if (niche.isEmpty()) allNiches = false;
      else niches.add(niche);
    }
    String platform = platforms.size() == 1 ? platforms.iterator().next() : null;
    String niche =
        platform != null && !platform.isEmpty() && allNiches && niches.size() == 1
            ? niches.iterator().next()
            : null;
    var proof =
        s.json.createObjectNode().put("schemaVersion", 1).put("metricSource", "SELF_REPORTED");
    proof
        .put("reviewId", review.toString())
        .put("reviewedAt", ((Timestamp) r.get("reviewed_at")).toInstant().toString());
    proof
        .put("overallRating", ((Number) r.get("overall_rating")).intValue())
        .put("brandFitRating", ((Number) r.get("brand_fit_rating")).intValue());
    proof.set("agreement", context);
    proof.set("onTime", s.json.valueToTree(onTime));
    proof.set("kpi", s.json.valueToTree(kpi));
    proof.set("platform", s.json.valueToTree(platform));
    proof.set("niche", s.json.valueToTree(niche));
    proof.set("acceptanceId", s.json.valueToTree(r.get("acceptance_id")));
    proof.set("outcomeRevision", s.json.valueToTree(r.get("accepted_outcome_revision")));
    proof.set("units", s.json.valueToTree(units));
    s.db.update(
        "update historical_campaign set learning_snapshot=?::jsonb where source_review_id=? and"
            + " source_revision=1",
        proof.toString(),
        review);
  }
}
