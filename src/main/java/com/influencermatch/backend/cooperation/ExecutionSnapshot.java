package com.influencermatch.backend.cooperation;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.exception.ValidationException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Called inside the confirmation transaction; never reconstructs historical agreement terms. */
@Component
@RequiredArgsConstructor
public class ExecutionSnapshot {
  private final DealStore store;

  public void create(UUID collaboration, UUID proposal, JsonNode items, String source) {
    if (!items.isArray() || items.isEmpty()) throw new ValidationException("Deliverables required");
    int total = 0;
    for (var item : items) {
      if (!item.path("dueAt").isTextual()
          || !item.path("acceptanceChecklist").isArray()
          || item.path("acceptanceChecklist").isEmpty()
          || !item.path("maxRevisionRounds").isIntegralNumber()
          || !item.path("maxRevisionRounds").canConvertToInt()
          || !item.path("quantity").isIntegralNumber()
          || !item.path("quantity").canConvertToInt())
        throw new ValidationException(
            "Deliverables require dueAt, acceptanceChecklist and maxRevisionRounds; submit a new"
                + " proposal");
      Instant due;
      try {
        due = Instant.parse(item.path("dueAt").asText());
      } catch (Exception e) {
        throw new ValidationException("Invalid deliverable deadline");
      }
      int rounds = item.path("maxRevisionRounds").intValue(),
          quantity = item.path("quantity").asInt();
      if (rounds < 0 || rounds > 10 || quantity < 1 || quantity > 100 || (total += quantity) > 1000)
        throw new ValidationException("Invalid deliverable limits");
      if (item.path("acceptanceChecklist").size() > 50)
        throw new ValidationException("Too many checklist items");
      java.util.Set<String> uniqueChecks = new java.util.HashSet<>();
      for (var check : item.path("acceptanceChecklist"))
        if (!check.isTextual()
            || check.asText().isBlank()
            || check.asText().length() > 500
            || !uniqueChecks.add(check.asText()))
          throw new ValidationException("Invalid acceptance checklist");
      UUID channel;
      try {
        channel = UUID.fromString(item.path("creatorId").asText());
      } catch (Exception e) {
        throw new ValidationException("Invalid deliverable channel");
      }
      if (store.db.queryForObject(
              "select count(*) from collaboration_channel where collaboration_id=? and"
                  + " creator_id=?",
              Long.class,
              collaboration,
              channel)
          != 1) throw new ValidationException("Deliverable channel is not a collaboration member");
      String type = item.path("contentType").asText();
      if (type.isBlank() || type.length() > 100)
        throw new ValidationException("Invalid content type");
      for (int n = 0; n < quantity; n++)
        store.db.update(
            "insert into"
                + " collaboration_deliverable(id,collaboration_id,creator_id,proposal_id,content_type,due_at,acceptance_checklist,max_revision_rounds,source,created_at)"
                + " values(?,?,?,?,?,?,?::jsonb,?,?,?)",
            UUID.randomUUID(),
            collaboration,
            channel,
            proposal,
            type,
            java.sql.Timestamp.from(due),
            item.path("acceptanceChecklist").toString(),
            rounds,
            source,
            store.time());
    }
    store.db.update(
        "update collaboration set execution_source=?,execution_revision=execution_revision+1 where"
            + " id=?",
        source,
        collaboration);
  }
}
