package com.influencermatch.backend.cooperation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.exception.ValidationException;
import java.math.*;
import java.time.Instant;
import java.util.*;

/** Pure calculation from a single immutable measurement revision. No absent value becomes zero. */
public final class OutcomeFinance {
  public static final String POLICY = "outcome-finance-v1";

  private OutcomeFinance() {}

  public static BigDecimal amount(JsonNode value) {
    if (value == null || value.isNull()) return null;
    if (!value.isNumber()) throw new ValidationException("Money must be a JSON number");
    BigDecimal n = value.decimalValue();
    if (n.signum() < 0 || n.scale() > 2 || n.precision() - n.scale() > 17)
      throw new ValidationException("Invalid VND amount");
    return n;
  }

  public static Long metric(JsonNode v) {
    if (v == null || v.isNull()) return null;
    if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0)
      throw new ValidationException("Metric must be a nonnegative integer");
    return v.longValue();
  }

  public static ObjectNode calculate(ObjectMapper mapper, JsonNode payload) {
    ObjectNode out = mapper.createObjectNode();
    out.put("formulaVersion", POLICY);
    out.put("metricSource", "SELF_REPORTED");
    if ((payload.hasNonNull("currency") && !"VND".equals(payload.path("currency").asText()))
        || ((payload.hasNonNull("costs")
                || payload.hasNonNull("gmv")
                || payload.hasNonNull("revenue"))
            && !"VND".equals(payload.path("currency").asText())))
      throw new ValidationException("Only VND supported for financial measurements");
    BigDecimal cost = null;
    if (payload.hasNonNull("costs")) {
      if (!payload.path("costs").isArray()
          || payload.path("costs").isEmpty()
          || payload.path("costs").size() > 100)
        throw new ValidationException("Explicit cost components required");
      cost = BigDecimal.ZERO;
      for (var c : payload.path("costs")) {
        if (!c.path("description").isTextual()
            || c.path("description").asText().isBlank()
            || c.path("description").asText().length() > 500)
          throw new ValidationException("Cost description required");
        BigDecimal a = amount(c.get("amount"));
        if (a == null) throw new ValidationException("Cost amount required");
        cost = cost.add(a);
      }
      amount(mapper.valueToTree(cost));
      if (!"VND".equals(payload.path("currency").asText()))
        throw new ValidationException("Only VND supported");
    }
    if (cost == null) out.putNull("actualCost");
    else out.put("actualCost", cost);
    BigDecimal gmv = amount(payload.get("gmv")), revenue = amount(payload.get("revenue"));
    boolean period = true;
    String from = null, to = null;
    Map<String, BigDecimal> sums = new LinkedHashMap<>();
    Set<String> missing = new HashSet<>();
    for (String key : List.of("views", "clicks", "likes", "comments", "shares"))
      sums.put(key, BigDecimal.ZERO);
    var posts = payload.path("posts");
    if (!posts.isArray() || posts.isEmpty() || posts.size() > 1000)
      throw new ValidationException("Publication measurements required");
    for (var p : posts) {
      try {
        Instant start = Instant.parse(p.path("periodStart").asText()),
            end = Instant.parse(p.path("periodEnd").asText());
        if (end.isBefore(start)) throw new IllegalArgumentException();
        if (from == null) {
          from = start.toString();
          to = end.toString();
        } else if (!from.equals(start.toString()) || !to.equals(end.toString())) period = false;
      } catch (Exception e) {
        throw new ValidationException("Valid measurement period required");
      }
      for (String key : sums.keySet()) {
        Long n = metric(p.get(key));
        if (n == null) missing.add(key);
        else sums.put(key, sums.get(key).add(BigDecimal.valueOf(n)));
      }
    }
    for (String key : sums.keySet()) {
      if (sums.get(key).compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0)
        throw new ValidationException("Metric total exceeds supported range");
      if (missing.contains(key)) out.putNull(key);
      else out.put(key, sums.get(key));
    }
    boolean financePeriod =
        period
            && Objects.equals(from, period(payload.get("periodStart")))
            && Objects.equals(to, period(payload.get("periodEnd")));
    BigDecimal engagements =
        missing.stream().anyMatch(Set.of("likes", "comments", "shares")::contains)
            ? null
            : sums.get("likes").add(sums.get("comments")).add(sums.get("shares"));
    ratio(out, "roas", gmv, cost, financePeriod);
    ratio(
        out,
        "roi",
        revenue == null || cost == null ? null : revenue.subtract(cost),
        cost,
        financePeriod);
    ratio(
        out,
        "cpm",
        cost == null ? null : cost.multiply(BigDecimal.valueOf(1000)),
        missing.contains("views") ? null : sums.get("views"),
        financePeriod);
    ratio(out, "cpe", cost, engagements, financePeriod);
    return out;
  }

  static String period(JsonNode value) {
    if (value == null || value.isNull()) return null;
    try {
      if (!value.isTextual()) throw new IllegalArgumentException();
      return Instant.parse(value.asText()).toString();
    } catch (Exception e) {
      throw new ValidationException("Valid financial measurement period required");
    }
  }

  static void ratio(
      ObjectNode out, String key, BigDecimal numerator, BigDecimal denominator, boolean period) {
    if (!period || numerator == null || denominator == null || denominator.signum() <= 0) {
      out.putNull(key);
      out.put(
          key + "UnavailableReason",
          !period
              ? "INCONSISTENT_MEASUREMENT_PERIOD"
              : numerator == null || denominator == null ? "MISSING_INPUT" : "INVALID_DENOMINATOR");
    } else out.put(key, numerator.divide(denominator, 4, RoundingMode.HALF_UP));
  }
}
