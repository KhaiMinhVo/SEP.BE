package com.influencermatch.backend.cooperation;

import static com.influencermatch.backend.cooperation.DealStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.security.permission.Permission;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Crawled observations are immutable evidence, never automatic outcome acceptance. */
@Service
public class PostMetricCollections {
  private final CollaborationExecution execution;
  private final DealStore s;
  private final PostMetricsClient client;
  private final TransactionTemplate tx;
  private final ExecutionHistoryReader historyReader;

  public PostMetricCollections(CollaborationExecution execution, DealStore s,
      PostMetricsClient client, org.springframework.transaction.PlatformTransactionManager manager,
      ExecutionHistoryReader historyReader) {
    this.execution = execution;
    this.s = s;
    this.client = client;
    this.tx = new TransactionTemplate(manager);
    this.historyReader = historyReader;
  }

  public record Request(long expectedRevision) {}

  private Map<String, Object> publication(UUID id, UUID unit) {
    return s.one("select p.* from collaboration_publication p join collaboration_deliverable d"
        + " on d.id=p.deliverable_id where d.collaboration_id=? and d.id=?"
        + " and d.status='APPROVED' and p.submission_revision=d.submission_revision", id, unit);
  }

  public JsonNode history(UUID id, UUID unit) {
    execution.access(execution.initial(id), false, Permission.MANAGE_OWN_COLLABORATION);
    // Check the unit belongs to this collaboration, including when no publication exists yet.
    s.one("select id from collaboration_deliverable where collaboration_id=? and id=?", id, unit);
    var result = s.json.createArrayNode();
    s.db.queryForList("select id,publication_id,status,post_url,requested_at,collected_at,metrics,error_code,raw_response"
        + " from collaboration_post_metric_collection where collaboration_id=? and deliverable_id=?"
        + " order by requested_at desc,id desc limit 50", id, unit).forEach(row -> {
          row.put("metrics", s.decode(row.get("metrics")));
          var raw = s.decode(row.remove("raw_response"));
          if (raw.path("trackedAt").isTextual() && raw.path("trackedAt").asText().length() <= 80)
            row.put("upstream_tracked_at", raw.path("trackedAt").asText());
          if (raw.path("servedAt").isTextual() && raw.path("servedAt").asText().length() <= 80)
            row.put("served_at", raw.path("servedAt").asText());
          if (raw.path("metricStatus").isObject()) row.put("metric_status", raw.path("metricStatus"));
          if (raw.path("metricErrors").isObject()) row.put("metric_errors", raw.path("metricErrors"));
          if (raw.path("collection").isObject()) row.put("collection", raw.path("collection"));
          if (raw.path("platform").isTextual()) row.put("platform", raw.path("platform").asText());
          if (raw.path("content").path("postedAt").isTextual())
            row.put("posted_at", raw.path("content").path("postedAt").asText());
          if (raw.path("error").isObject()) {
            var upstreamError = raw.path("error");
            if (upstreamError.path("retryable").isBoolean())
              row.put("retryable", upstreamError.path("retryable").booleanValue());
            if (upstreamError.path("retryAfterSeconds").isIntegralNumber()
                && upstreamError.path("retryAfterSeconds").longValue() >= 0)
              row.put("retry_after_seconds", upstreamError.path("retryAfterSeconds").longValue());
            if (upstreamError.path("code").isTextual())
              row.put("upstream_error_code", upstreamError.path("code").asText());
          }
          if (raw.path("content").path("caption").isTextual())
            row.put("caption", raw.path("content").path("caption").asText().substring(0,
                Math.min(2000, raw.path("content").path("caption").asText().length())));
          result.add(s.json.valueToTree(historyReader.camelMap(row)));
        });
    return result;
  }

  public JsonNode collect(UUID id, UUID unit, Request request, String key) {
    if (key == null || key.isBlank() || key.length() > 200)
      throw new ValidationException("A bounded Idempotency-Key is required");
    var reservation = tx.execute(status -> {
      var c = execution.lock(id, false, Permission.MANAGE_OWN_COLLABORATION);
      var replay = s.optional("select * from collaboration_post_metric_collection where"
          + " collaboration_id=? and actor_id=? and request_key=?", id, execution.actor(), key);
      if (replay.isPresent()) {
        if (!unit.equals(replay.get().get("deliverable_id"))) throw conflict("Collection key scope changed");
        return Map.<String, Object>of("replay", true);
      }
      if (!List.of("IN_PROGRESS", "AWAITING_REVIEW", "COMPLETED").contains(c.get("status"))
          || !List.of("ACTIVE", "COMPLETED").contains(c.get("campaign_status")))
        throw conflict("Only a started collaboration can track published content");
      execution.revision(c, "execution_revision", request.expectedRevision());
      var p = publication(id, unit);
      String url = supportedUrl(p.get("url").toString());
      var recent = s.optional("select requested_at,status from collaboration_post_metric_collection"
          + " where publication_id=? order by requested_at desc limit 1", p.get("id"));
      if (recent.isPresent()) {
        var at = ((java.sql.Timestamp) recent.get().get("requested_at")).toInstant();
        long seconds = Duration.between(at, s.now()).getSeconds();
        if (seconds < 60 || ("PENDING".equals(recent.get().get("status")) && seconds < 120))
          throw conflict("Collection is pending or was requested recently; wait before refreshing");
      }
      UUID observation = UUID.randomUUID();
      s.db.update("update collaboration_post_metric_collection set status='FAILED',error_code='COLLECTION_TIMEOUT'"
          + " where publication_id=? and status='PENDING' and requested_at<?", p.get("id"),
          java.sql.Timestamp.from(s.now().minusSeconds(120)));
      s.db.update("insert into collaboration_post_metric_collection(id,collaboration_id,deliverable_id,"
          + "publication_id,actor_id,request_key,status,post_url,requested_at) values(?,?,?,?,?,?,'PENDING',?,?)",
          observation, id, unit, p.get("id"), execution.actor(), key, url, s.time());
      return Map.<String, Object>of("id", observation, "url", url, "publication", p.get("id"));
    });
    if (!reservation.containsKey("replay")) {
      JsonNode raw = null;
      ObjectNode metrics = null;
      String error = null;
      try {
        raw = client.collect(reservation.get("url").toString());
        if (raw == null || raw.toString().length() > 100_000) throw new IllegalArgumentException();
        if ("error".equals(raw.path("status").asText())) {
          error = aiErrorCode(raw);
        } else {
          if (!publicationMatches(raw, reservation.get("url").toString()))
            throw new IllegalArgumentException("Crawler returned another publication");
          if (allUnavailable(raw)) error = "NO_METRICS_AVAILABLE";
          else metrics = normalize(raw);
        }
      } catch (IllegalArgumentException e) {
        error = "UNSUPPORTED_CRAWLER_RESPONSE";
      } catch (Exception e) {
        error = "CRAWLER_UNAVAILABLE";
      }
      // No database transaction/row locks are held while calling the crawler.
      final JsonNode response = raw;
      final ObjectNode counts = metrics;
      final String failure = error;
      tx.executeWithoutResult(status -> {
        execution.access(execution.initial(id), false, Permission.MANAGE_OWN_COLLABORATION);
        s.db.update("update collaboration_post_metric_collection set status=?,collected_at=?,"
            + "metrics=?::jsonb,raw_response=?::jsonb,error_code=? where id=? and status='PENDING'",
            failure == null ? "SUCCEEDED" : "FAILED", s.time(), counts == null ? null : counts.toString(),
            response == null ? null : sanitizedResponse(response).toString(),
            failure, reservation.get("id"));
      });
    }
    return history(id, unit);
  }

  static String supportedUrl(String value) {
    String url = CollaborationExecution.https(value);
    var uri = URI.create(url);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    boolean supported = List.of("tiktok.com", "youtube.com", "youtu.be", "facebook.com", "instagram.com")
        .stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    if (!supported || (uri.getPort() != -1 && uri.getPort() != 443))
      throw new ValidationException("Only supported social platform publication URLs can be collected");
    return url;
  }

  static String aiErrorCode(JsonNode raw) {
    String code = raw.path("error").path("code").asText("");
    if (List.of(
        "INVALID_POST_URL",
        "UNSUPPORTED_PLATFORM",
        "POST_NOT_FOUND",
        "POST_PRIVATE_OR_UNAVAILABLE",
        "RATE_LIMITED",
        "UPSTREAM_TIMEOUT",
        "UPSTREAM_UNAVAILABLE",
        "PARSE_FAILED",
        "NO_METRICS_AVAILABLE").contains(code)) return code;
    return "UNSUPPORTED_CRAWLER_RESPONSE";
  }

  static boolean publicationMatches(JsonNode raw, String publicationUrl) {
    if (raw == null || !raw.isObject()) return false;
    String expected = comparablePublicationUrl(publicationUrl);
    for (String field : List.of("canonicalPostUrl", "postUrl")) {
      if (!raw.path(field).isTextual()) continue;
      try {
        if (comparablePublicationUrl(supportedUrl(raw.path(field).asText())).equals(expected)) return true;
      } catch (RuntimeException ignored) {
        return false;
      }
    }
    return false;
  }

  static String comparablePublicationUrl(String url) {
    var uri = URI.create(supportedUrl(url));
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    String path = uri.getPath();
    if (path == null || path.isBlank()) path = "/";
    if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
    return "https://" + host + path;
  }

  static JsonNode sanitizedResponse(JsonNode raw) {
    if (raw == null || !raw.isObject()) return com.fasterxml.jackson.databind.node.NullNode.instance;
    var out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    for (String field : List.of("schemaVersion", "status", "platform", "postUrl", "canonicalPostUrl",
        "postId", "metrics", "metricStatus", "metricErrors", "trackedAt", "servedAt",
        "collection", "warnings")) {
      if (raw.has(field) && !raw.get(field).isNull()) out.set(field, raw.get(field));
    }
    if (raw.path("content").isObject()) {
      var content = raw.path("content");
      var safeContent = out.putObject("content");
      for (String field : List.of("caption", "postedAt")) {
        if (content.has(field) && content.get(field).isTextual())
          safeContent.set(field, content.get(field));
      }
    }
    if (raw.path("error").isObject()) out.set("error", raw.path("error"));
    return out;
  }

  static boolean allUnavailable(JsonNode raw) {
    JsonNode statuses = raw.path("metricStatus");
    if (!statuses.isObject()) return false;
    boolean hasKnownStatus = false;
    for (String metric : List.of("views", "likes", "comments", "shares")) {
      if (!statuses.has(metric)) continue;
      hasKnownStatus = true;
      if ("AVAILABLE".equals(statuses.path(metric).asText())) return false;
    }
    return hasKnownStatus;
  }

  /** Conservative adapter. Unknown/missing metrics remain unavailable, never zero. */
  static ObjectNode normalize(JsonNode raw) {
    if (!raw.isObject() || !"success".equals(raw.path("status").asText())
        || raw.hasNonNull("error")) throw new IllegalArgumentException("Crawler unsuccessful");
    JsonNode data = raw.path("metrics");
    var out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    for (String metric : List.of("views", "likes", "comments", "shares")) {
      var count = data.path(metric);
      var state = raw.path("metricStatus").path(metric);
      if (!state.isMissingNode()) {
        if (!state.isTextual() || !List.of("AVAILABLE", "NOT_SUPPORTED", "NOT_AVAILABLE", "FETCH_FAILED").contains(state.asText()))
          throw new IllegalArgumentException("Unknown metric availability");
        if (!"AVAILABLE".equals(state.asText())) {
          if (!count.isMissingNode() && !count.isNull()) throw new IllegalArgumentException("Unavailable metric must be null");
          continue;
        }
        if (count.isMissingNode() || count.isNull()) throw new IllegalArgumentException("Available metric is missing");
      }
      if (!count.isMissingNode() && !count.isNull()) {
        if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0
            || count.longValue() > 9_007_199_254_740_991L) throw new IllegalArgumentException("Invalid counter");
        // Deployed crawler currently returns zero for unavailable counters, without availability flags.
        // Until that contract is improved, zero is ambiguous and must not become a confirmed KPI.
        if (count.longValue() > 0 || "AVAILABLE".equals(state.asText()) || raw.path("metricAvailability").path(metric).asBoolean(false))
          out.set(metric, count);
      }
    }
    if (out.isEmpty()) throw new IllegalArgumentException("No recognized metrics");
    return out;
  }
}
