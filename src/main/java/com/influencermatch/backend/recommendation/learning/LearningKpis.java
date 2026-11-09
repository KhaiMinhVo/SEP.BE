package com.influencermatch.backend.recommendation.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.exception.ValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** No financial attribution, inferred zeros, provider requests or client-selected quality. */
public final class LearningKpis {
  private LearningKpis() {}

  public record Target(
      String key,
      UUID creatorId,
      @io.swagger.v3.oas.annotations.media.Schema(
              allowableValues = {"VIEWS", "CLICKS", "ENGAGEMENTS"})
          String metric,
      @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          Long target,
      Instant periodStart,
      Instant periodEnd) {}

  public static void validate(List<Target> targets, Set<UUID> members, Instant confirmAt) {
    if (targets == null || targets.size() > 20) throw invalid();
    Set<String> keys = new HashSet<>(), metrics = new HashSet<>();
    Map<UUID, String> periods = new HashMap<>();
    for (var t : targets) {
      if (t == null
          || t.key == null
          || !t.key.matches("[A-Za-z0-9_-]{1,50}")
          || !keys.add(t.key)
          || t.creatorId == null
          || !members.contains(t.creatorId)
          || !Set.of("VIEWS", "CLICKS", "ENGAGEMENTS").contains(t.metric == null ? "" : t.metric)
          || t.target == null
          || t.target <= 0
          || t.periodStart == null
          || t.periodEnd == null
          || !t.periodEnd.isAfter(t.periodStart)
          || confirmAt != null && !t.periodStart.isAfter(confirmAt)
          || !metrics.add(t.creatorId + ":" + t.metric)) throw invalid();
      String period = t.periodStart + "/" + t.periodEnd;
      if (!period.equals(periods.computeIfAbsent(t.creatorId, k -> period))) throw invalid();
    }
  }

  private static ValidationException invalid() {
    return new ValidationException(
        "Invalid KPI targets: channel, positive target, unique metric/key and future common UTC"
            + " period required");
  }

  public record Publication(UUID deliverableId, UUID creatorId, long revision, String postKey) {}

  public static Double attainment(
      JsonNode targets, JsonNode posts, List<Publication> publications, Instant now) {
    if (!targets.isArray() || targets.isEmpty() || !posts.isArray()) return null;
    double total = 0;
    for (var t : targets) {
      UUID channel;
      Instant start, end;
      try {
        channel = UUID.fromString(t.path("creatorId").asText());
        start = Instant.parse(t.path("periodStart").asText());
        end = Instant.parse(t.path("periodEnd").asText());
      } catch (Exception e) {
        return null;
      }
      if (now.isBefore(end)
          || !end.isAfter(start)
          || !t.path("target").isIntegralNumber()
          || !t.path("target").canConvertToLong()
          || t.path("target").asLong() <= 0) return null;
      var required = publications.stream().filter(p -> p.creatorId.equals(channel)).toList();
      if (required.isEmpty()) return null;
      Map<String, BigDecimal> unique = new HashMap<>();
      for (var pub : required) {
        var measurements = new ArrayList<JsonNode>();
        posts.forEach(
            p -> {
              if (pub.deliverableId.toString().equals(p.path("deliverableId").asText())
                  && p.path("submissionRevision").asLong() == pub.revision) measurements.add(p);
            });
        if (measurements.size() != 1) return null;
        var p = measurements.getFirst();
        try {
          if (!Instant.parse(p.path("periodStart").asText()).equals(start)
              || !Instant.parse(p.path("periodEnd").asText()).equals(end)) return null;
        } catch (Exception e) {
          return null;
        }
        BigDecimal actual = value(p, t.path("metric").asText());
        if (actual == null) return null;
        var existing = unique.putIfAbsent(pub.postKey, actual);
        if (existing != null && existing.compareTo(actual) != 0) return null;
      }
      BigDecimal actual = unique.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      total +=
          Math.min(
              1,
              actual
                  .divide(t.path("target").decimalValue(), java.math.MathContext.DECIMAL128)
                  .doubleValue());
    }
    return total / targets.size();
  }

  private static BigDecimal value(JsonNode p, String metric) {
    var fields =
        switch (metric) {
          case "VIEWS" -> List.of("views");
          case "CLICKS" -> List.of("clicks");
          case "ENGAGEMENTS" -> List.of("likes", "comments", "shares");
          default -> List.<String>of();
        };
    if (fields.isEmpty()) return null;
    BigDecimal total = BigDecimal.ZERO;
    for (var f : fields) {
      var v = p.path(f);
      if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0) return null;
      total = total.add(v.decimalValue());
    }
    return total;
  }
}
