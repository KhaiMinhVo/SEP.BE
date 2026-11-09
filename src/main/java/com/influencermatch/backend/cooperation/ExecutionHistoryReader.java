package com.influencermatch.backend.cooperation;

import static com.influencermatch.backend.cooperation.DealStore.uuid;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Internal query component. Callers must authorize ownership before requesting private history. */
@Component
@RequiredArgsConstructor
public class ExecutionHistoryReader {
  private final DealStore s;

  Map<String, Object> initial(UUID id) {
    return s.one(
        "select c.*,ca.status campaign_status from collaboration c join campaign ca on"
            + " ca.id=c.campaign_id where c.id=?",
        id);
  }

  /** Shared immutable negotiation events. The caller supplies its own read authorization. */
  public List<Map<String, Object>> dealEvents(UUID id) {
    return s.db.queryForList(
        "select id,actor_id,action,revision,metadata,created_at from cooperation_history where"
            + " deal_id=? order by created_at,id",
        id);
  }

  public Map<String, Object> snapshot(UUID id) {
    var out = new LinkedHashMap<>(initial(id));
    out.remove("brand_user_id");
    out.put("deliverables", units(id));
    var outcomes =
        s.db.queryForList(
            "select * from collaboration_outcome_revision where collaboration_id=? order by"
                + " revision",
            id);
    for (var o : outcomes) {
      o.put("payload", s.decode(o.get("payload")));
      o.put("calculation", s.decode(o.get("calculation")));
    }
    out.put("outcomeRevisions", outcomes);
    out.put(
        "acceptances",
        s.db.queryForList(
            "select * from collaboration_outcome_acceptance where collaboration_id=? order by"
                + " accepted_at",
            id));
    out.put(
        "review",
        s.optional("select * from creator_review where collaboration_id=?", id)
            .map(
                v -> {
                  v.put("review_tags", s.decode(v.get("review_tags")));
                  return v;
                })
            .orElse(null));
    return camelMap(out);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> camelMap(Map<String, Object> input) {
    Map<String, Object> out = new LinkedHashMap<>();
    input.forEach(
        (k, v) -> {
          StringBuilder key = new StringBuilder();
          boolean upper = false;
          for (char ch : k.toCharArray()) {
            if (ch == '_') {
              upper = true;
              continue;
            }
            key.append(upper ? Character.toUpperCase(ch) : ch);
            upper = false;
          }
          out.put(key.toString(), camelValue(v));
        });
    return out;
  }

  @SuppressWarnings("unchecked")
  Object camelValue(Object value) {
    if (value == null) return com.fasterxml.jackson.databind.node.NullNode.instance;
    if (value instanceof Map<?, ?> map) return camelMap((Map<String, Object>) map);
    if (value instanceof List<?> list) return list.stream().map(this::camelValue).toList();
    return value;
  }

  public List<Map<String, Object>> units(UUID id) {
    var rows =
        s.db.queryForList(
            "select * from collaboration_deliverable where collaboration_id=? order by"
                + " created_at,id",
            id);
    var submissions =
        group(
            s.db.queryForList(
                "select a.* from collaboration_submission a join collaboration_deliverable d on"
                    + " d.id=a.deliverable_id where d.collaboration_id=? order by"
                    + " a.deliverable_id,a.revision",
                id));
    var reviews =
        s.db.queryForList(
            "select a.* from collaboration_content_review a join collaboration_deliverable d on"
                + " d.id=a.deliverable_id where d.collaboration_id=? order by"
                + " a.deliverable_id,a.created_at,a.id",
            id);
    for (var review : reviews) review.put("checklist", s.decode(review.get("checklist")));
    var decisions = group(reviews);
    var publications =
        group(
            s.db.queryForList(
                "select a.* from collaboration_publication a join collaboration_deliverable d on"
                    + " d.id=a.deliverable_id where d.collaboration_id=? order by"
                    + " a.deliverable_id,a.created_at,a.id",
                id));
    for (var row : rows) {
      UUID unit = uuid(row.get("id"));
      row.put("acceptance_checklist", s.decode(row.get("acceptance_checklist")));
      row.put("submissions", submissions.getOrDefault(unit, List.of()));
      row.put("reviews", decisions.getOrDefault(unit, List.of()));
      row.put("publications", publications.getOrDefault(unit, List.of()));
    }
    return rows;
  }

  Map<UUID, List<Map<String, Object>>> group(List<Map<String, Object>> rows) {
    return rows.stream()
        .collect(java.util.stream.Collectors.groupingBy(row -> uuid(row.get("deliverable_id"))));
  }
}
