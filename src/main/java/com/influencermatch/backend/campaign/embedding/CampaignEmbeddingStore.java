package com.influencermatch.backend.campaign.embedding;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.exception.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class CampaignEmbeddingStore {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final ObjectMapper mapper;
  private final ApplicationEventPublisher events;

  @Value("${application.campaign-embedding.model:bkai-foundation-models/vietnamese-bi-encoder}")
  private String model;

  @Value("${application.campaign-embedding.model-version:m3-embed-v1}")
  private String version;

  public record Wake(UUID job) {}

  public record View(String status, String model, String modelVersion, Instant readyAt) {}

  public record Lease(
      UUID id, UUID token, int attempts, String model, String version, String text) {}

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(UUID context) {
    UUID id = UUID.randomUUID();
    int inserted =
        jdbc.update(
            "insert into"
                + " campaign_embedding_job(id,context_id,model_id,model_version,status,next_attempt_at,created_at,updated_at)"
                + " values(?,?,?,?,'PENDING',?,?,?) on conflict(context_id,model_id,model_version)"
                + " do nothing",
            id,
            context,
            model,
            version,
            now(),
            now(),
            now());
    if (inserted > 0) events.publishEvent(new Wake(id));
  }

  public View view(UUID context) {
    var rows =
        jdbc.query(
            "select status,ready_at from campaign_embedding_job where context_id=? and model_id=?"
                + " and model_version=?",
            (r, n) ->
                new View(
                    r.getString(1),
                    model,
                    version,
                    r.getTimestamp(2) == null ? null : r.getTimestamp(2).toInstant()),
            context,
            model,
            version);
    return rows.isEmpty() ? new View("PENDING", model, version, null) : rows.getFirst();
  }

  @Transactional
  public void ensureCurrentJobs() {
    jdbc.update(
        "insert into"
            + " campaign_embedding_job(id,context_id,model_id,model_version,status,next_attempt_at,created_at,updated_at)"
            + " select gen_random_uuid(),c.id,?,?,'PENDING',?,?,? from campaign_context c where"
            + " c.embedding_source_context_id=c.id on conflict(context_id,model_id,model_version)"
            + " do nothing",
        model,
        version,
        now(),
        now(),
        now());
  }

  public record Ready(
      UUID jobId, UUID contextId, String model, String modelVersion, String text, String vector) {}

  public Ready ready(UUID context) {
    var rows =
        jdbc.query(
            "select j.id,j.embedding::text,c.context_data::text from campaign_embedding_job j join"
                + " campaign_context c on c.id=j.context_id where j.context_id=? and j.model_id=?"
                + " and j.model_version=? and j.status='READY'",
            (r, n) ->
                new Ready(
                    r.getObject(1, UUID.class),
                    context,
                    model,
                    version,
                    readText(r.getString(3)),
                    r.getString(2)),
            context,
            model,
            version);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private String readText(String json) {
    try {
      return mapper.readTree(json).path("embeddingText").asText();
    } catch (Exception e) {
      throw new IllegalStateException("Invalid Campaign context");
    }
  }

  public List<UUID> due() {
    return jdbc.queryForList(
        "select id from campaign_embedding_job where (status='PENDING' and next_attempt_at<=?) or"
            + " (status='PROCESSING' and lease_until<=?) order by next_attempt_at,id limit 100",
        UUID.class,
        now(),
        now());
  }

  @Transactional
  public Lease claim(UUID id) {
    var rows =
        jdbc.queryForList(
            "select j.*,c.context_data::text as context_json from campaign_embedding_job j join"
                + " campaign_context c on c.id=j.context_id where j.id=? for update of j",
            id);
    if (rows.isEmpty()) return null;
    var r = rows.getFirst();
    String state = (String) r.get("status");
    if (!("PENDING".equals(state)
            && !((Timestamp) r.get("next_attempt_at")).toInstant().isAfter(clock.instant()))
        && !("PROCESSING".equals(state)
            && r.get("lease_until") != null
            && !((Timestamp) r.get("lease_until")).toInstant().isAfter(clock.instant())))
      return null;
    int attempts = ((Number) r.get("attempts")).intValue();
    if (attempts >= 5) {
      jdbc.update(
          "update campaign_embedding_job set"
              + " status='FAILED',processing_token=null,lease_until=null,updated_at=? where id=?",
          now(),
          id);
      return null;
    }
    UUID token = UUID.randomUUID();
    jdbc.update(
        "update campaign_embedding_job set"
            + " status='PROCESSING',attempts=attempts+1,processing_token=?,lease_until=?,updated_at=?"
            + " where id=?",
        token,
        Timestamp.from(clock.instant().plusSeconds(120)),
        now(),
        id);
    try {
      return new Lease(
          id,
          token,
          attempts + 1,
          (String) r.get("model_id"),
          (String) r.get("model_version"),
          mapper.readTree((String) r.get("context_json")).path("embeddingText").asText());
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid persisted Brand context", ex);
    }
  }

  @Transactional
  public boolean finish(Lease lease, List<Float> vector) {
    String value = vector.toString();
    return jdbc.update(
            "update campaign_embedding_job set status='READY',embedding=cast(? as"
                + " vector),ready_at=?,updated_at=?,lease_until=null,processing_token=null where"
                + " id=? and status='PROCESSING' and processing_token=? and lease_until>?",
            value,
            now(),
            now(),
            lease.id(),
            lease.token(),
            now())
        == 1;
  }

  @Transactional
  public void fail(Lease lease) {
    long wait = 15L << Math.min(3, lease.attempts() - 1);
    jdbc.update(
        "update campaign_embedding_job set"
            + " status=?,next_attempt_at=?,updated_at=?,lease_until=null,processing_token=null"
            + " where id=? and status='PROCESSING' and processing_token=? and lease_until>?",
        lease.attempts() >= 5 ? "FAILED" : "PENDING",
        Timestamp.from(clock.instant().plusSeconds(wait)),
        now(),
        lease.id(),
        lease.token(),
        now());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void retry(UUID context) {
    var rows =
        jdbc.queryForList(
            "select id,status from campaign_embedding_job where context_id=? and model_id=? and"
                + " model_version=? for update",
            context,
            model,
            version);
    if (rows.isEmpty() || !"FAILED".equals(rows.getFirst().get("status")))
      throw new BusinessException(
          ErrorCode.CAMPAIGN_EMBEDDING_RETRY_CONFLICT,
          "Only a failed current-context job can be retried");
    UUID id = (UUID) rows.getFirst().get("id");
    jdbc.update(
        "update campaign_embedding_job set"
            + " status='PENDING',attempts=0,next_attempt_at=?,updated_at=?,processing_token=null,lease_until=null"
            + " where id=?",
        now(),
        now(),
        id);
    events.publishEvent(new Wake(id));
  }
}
