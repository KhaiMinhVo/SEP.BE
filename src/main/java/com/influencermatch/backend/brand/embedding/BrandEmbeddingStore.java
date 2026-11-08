package com.influencermatch.backend.brand.embedding;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.brand.service.BrandContextData;
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
public class BrandEmbeddingStore {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final ObjectMapper mapper;
  private final ApplicationEventPublisher events;

  @Value("${application.brand-embedding.model:bkai-foundation-models/vietnamese-bi-encoder}")
  private String model;

  @Value("${application.brand-embedding.model-version:m4-embed-v1}")
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
                + " brand_embedding_job(id,context_id,model_id,model_version,status,next_attempt_at,created_at,updated_at)"
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
            "select status,ready_at from brand_embedding_job where context_id=? and model_id=? and"
                + " model_version=?",
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
            + " brand_embedding_job(id,context_id,model_id,model_version,status,next_attempt_at,created_at,updated_at)"
            + " select gen_random_uuid(),c.id,?,?,'PENDING',?,?,? from brand_context c where not"
            + " exists(select 1 from brand_context newer where"
            + " newer.brand_profile_id=c.brand_profile_id and"
            + " newer.context_version>c.context_version) on"
            + " conflict(context_id,model_id,model_version) do nothing",
        model,
        version,
        now(),
        now(),
        now());
  }

  public List<UUID> due() {
    return jdbc.queryForList(
        "select id from brand_embedding_job where (status='PENDING' and next_attempt_at<=?) or"
            + " (status='PROCESSING' and lease_until<=?) order by next_attempt_at,id limit 100",
        UUID.class,
        now(),
        now());
  }

  @Transactional
  public Lease claim(UUID id) {
    var rows =
        jdbc.queryForList(
            "select j.*,c.profile_context::text as context_json from brand_embedding_job j join"
                + " brand_context c on c.id=j.context_id where j.id=? for update of j",
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
          "update brand_embedding_job set"
              + " status='FAILED',processing_token=null,lease_until=null,updated_at=? where id=?",
          now(),
          id);
      return null;
    }
    UUID token = UUID.randomUUID();
    jdbc.update(
        "update brand_embedding_job set"
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
          BrandContextData.embeddingText(mapper.readTree((String) r.get("context_json"))));
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid persisted Brand context", ex);
    }
  }

  @Transactional
  public boolean finish(Lease lease, List<Float> vector) {
    String value = vector.toString();
    return jdbc.update(
            "update brand_embedding_job set status='READY',embedding=cast(? as"
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
        "update brand_embedding_job set"
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
            "select id,status from brand_embedding_job where context_id=? and model_id=? and"
                + " model_version=? for update",
            context,
            model,
            version);
    if (rows.isEmpty() || !"FAILED".equals(rows.getFirst().get("status")))
      throw new BusinessException(
          ErrorCode.BRAND_EMBEDDING_RETRY_CONFLICT,
          "Only a failed current-context job can be retried");
    UUID id = (UUID) rows.getFirst().get("id");
    jdbc.update(
        "update brand_embedding_job set"
            + " status='PENDING',attempts=0,next_attempt_at=?,updated_at=?,processing_token=null,lease_until=null"
            + " where id=?",
        now(),
        now(),
        id);
    events.publishEvent(new Wake(id));
  }
}
