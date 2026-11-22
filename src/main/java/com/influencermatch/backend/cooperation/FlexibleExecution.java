package com.influencermatch.backend.cooperation;

import static com.influencermatch.backend.cooperation.DealStore.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.security.permission.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit user actions only. No timeout implies consent, completion, payment or a rating. */
@Service
@RequiredArgsConstructor
public class FlexibleExecution {
  private final CollaborationExecution execution;
  private final DealService deals;
  private final DealStore s;

  @Transactional
  public JsonNode mode(UUID id, FlexibleDtos.Mode r, String key) {
    var c = execution.lock(id, true, Permission.MANAGE_OWN_COLLABORATION);
    String scope = "execution-mode:" + id;
    var old = deals.replay(scope, key, r);
    if (old != null) return old;
    execution.revision(c, "execution_revision", r.expectedRevision());
    if (!"PLANNED".equals(c.get("status"))) throw conflict("Choose management mode before starting");
    s.one("select id from cooperation_deal where collaboration_id=? and status='CONFIRMED'", id);
    s.db.update("update collaboration set management_mode=? where id=?", r.managementMode(), id);
    execution.changed(id, "MANAGEMENT_MODE_SELECTED", Map.of("managementMode", r.managementMode()));
    return deals.remembered(scope, key, r, execution.view(id));
  }

  @Transactional
  public JsonNode completeWithoutReview(UUID id, FlexibleDtos.Complete r, String key) {
    var c = execution.lock(id, true, Permission.RECORD_OWN_OUTCOME);
    Permissions.require(Permission.MANAGE_OWN_COLLABORATION);
    deals.fresh(Permission.MANAGE_OWN_COLLABORATION);
    String scope = "complete-without-review:" + id;
    var old = deals.replay(scope, key, r);
    if (old != null) return old;
    execution.revision(c, "execution_revision", r.expectedRevision());
    if (!"AWAITING_REVIEW".equals(c.get("status"))) throw conflict("Accept the current outcome before completion");
    var a = s.one(
        "select * from collaboration_outcome_acceptance where collaboration_id=? and reopened_at is null",
        id);
    execution.revision(a, "outcome_revision", r.outcomeRevision());
    var latest = s.one("select revision from collaboration_outcome_revision where collaboration_id=? order by revision desc limit 1", id);
    execution.revision(latest, "revision", r.outcomeRevision());
    UUID outcomeId = execution.persistAcceptedOutcome(id, r.outcomeRevision());
    var completedAt = s.time();
    s.db.update("update collaboration set status='COMPLETED',completion_method='WITHOUT_REVIEW',completed_at=?,"
        + "published_content_url=(select p.url from collaboration_publication p join collaboration_deliverable d"
        + " on d.id=p.deliverable_id where d.collaboration_id=? and p.submission_revision=d.submission_revision order by d.id limit 1) where id=?", completedAt, id, id);
    execution.recordCompletionEvidence(
        id,
        outcomeId,
        r.outcomeRevision(),
        null,
        null,
        (java.sql.Timestamp) a.get("accepted_at"));
    execution.changed(id, "COMPLETED_WITHOUT_REVIEW", Map.of("outcomeRevision", r.outcomeRevision()));
    return deals.remembered(scope, key, r, execution.view(id));
  }

  @Transactional
  public JsonNode requestTermination(UUID id, FlexibleDtos.Exit r, String key) {
    var c = execution.lock(id, false, Permission.MANAGE_OWN_COLLABORATION);
    String scope = "request-termination:" + id;
    var old = deals.replay(scope, key, r);
    if (old != null) return old;
    execution.progress(c);
    execution.revision(c, "execution_revision", r.expectedRevision());
    evidence(r);
    if (s.optional("select id from collaboration_exit_request where collaboration_id=? and resolved_at is null", id).isPresent())
      throw conflict("A termination request is already pending");
    s.db.update("insert into collaboration_exit_request(id,collaboration_id,requested_by,reason,evidence_url,created_at) values(?,?,?,?,?,?)",
        UUID.randomUUID(), id, execution.actor(), r.reason().strip(), r.evidenceUrl(), s.time());
    execution.changed(id, "TERMINATION_REQUESTED");
    return deals.remembered(scope, key, r, execution.view(id));
  }

  @Transactional
  public JsonNode terminate(UUID id, FlexibleDtos.Exit r, String key, boolean decline) {
    var c = execution.lock(id, false, Permission.MANAGE_OWN_COLLABORATION);
    String scope = (decline ? "decline-termination:" : "terminate:") + id;
    var old = deals.replay(scope, key, r);
    if (old != null) return old;
    execution.progress(c);
    execution.revision(c, "execution_revision", r.expectedRevision());
    evidence(r);
    if (r.requestId() == null) throw new ValidationException("A pending termination request is required");
    var request = s.one("select r.*,u.role requested_by_role from collaboration_exit_request r"
        + " join \"user\" u on u.id=r.requested_by where r.id=? and r.collaboration_id=?"
        + " and r.resolved_at is null for update", r.requestId(), id);
    boolean requesterIsBrand = "BRAND".equals(request.get("requested_by_role"));
    if (requesterIsBrand == execution.isBrand(c))
      throw new ValidationException("Only the other participant can resolve a termination request");
    if (decline) {
      s.db.update("update collaboration_exit_request set resolved_at=?,resolved_by=?,resolution='DECLINED',resolution_reason=? where id=?",
          s.time(), execution.actor(), r.reason().strip(), r.requestId());
    } else {
      s.db.update("update collaboration set status='TERMINATED',terminated_at=?,termination_reason=?,"
              + "termination_evidence=? where id=?",
          s.time(), request.get("reason"), request.get("evidence_url") != null
              ? request.get("evidence_url") : r.evidenceUrl(), id);
      s.db.update("update collaboration_exit_request set resolved_at=?,resolved_by=?,resolution='TERMINATED',resolution_reason=? where id=?",
          s.time(), execution.actor(), r.reason().strip(), r.requestId());
      // Work, expenses, agreed commitment and payment status remain unchanged.
      // A terminated collaboration produces neither a final rating nor successful historical evidence.
    }
    execution.changed(id, decline ? "TERMINATION_REQUEST_DECLINED" : "COLLABORATION_TERMINATED",
        Map.of("reason", r.reason().strip(), "requestId", r.requestId()));
    return deals.remembered(scope, key, r, execution.view(id));
  }

  private void evidence(FlexibleDtos.Exit r) {
    if (r.evidenceUrl() != null && !r.evidenceUrl().isBlank()) CollaborationExecution.https(r.evidenceUrl());
  }
}
