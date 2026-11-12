package com.influencermatch.backend.campaign.history;

import static com.influencermatch.backend.campaign.history.HistoryDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.cooperation.OutcomeFinance;
import java.math.*;
import java.time.Instant;
import java.util.*;

/** Read-time aggregation only. Neither persisted outcomes nor old recommendation scores change. */
public final class CampaignHistoryCalculator {
  public static final String POLICY = "campaign-history-report-v1";
  private static final List<String> COUNTS =
      List.of("views", "clicks", "likes", "comments", "shares");

  private CampaignHistoryCalculator() {}

  public record Source(
      UUID id,
      String status,
      BigDecimal agreedFee,
      String currency,
      boolean eligible,
      JsonNode payload,
      JsonNode calculation,
      Map<String, String> publicationKeys) {}

  public static Report calculate(List<Source> sources) {
    var completed = sources.stream().filter(s -> "COMPLETED".equals(s.status)).toList();
    var eligible = completed.stream().filter(Source::eligible).toList();
    var missing = completed.stream().filter(s -> !s.eligible).map(Source::id).sorted().toList();
    int cancelled = (int) sources.stream().filter(s -> "CANCELLED".equals(s.status)).count();
    Set<String> common = new TreeSet<>();
    if (completed.isEmpty()) common.add("NO_COMPLETED_COLLABORATIONS");
    if (!missing.isEmpty()) common.add("MISSING_ACCEPTED_REVIEWED_SOURCE");
    Map<String, Metric> result = new LinkedHashMap<>();
    BigDecimal committed = BigDecimal.ZERO;
    Set<String> commitmentReasons = new TreeSet<>();
    for (var s : sources)
      if (!"CANCELLED".equals(s.status)) {
        if (!"VND".equals(s.currency)) commitmentReasons.add("UNSUPPORTED_CURRENCY");
        if (s.agreedFee == null || s.agreedFee.signum() < 0)
          commitmentReasons.add("MISSING_COMMITMENT");
        else committed = committed.add(s.agreedFee);
      }
    result.put(
        "committedFee",
        metric(
            committed,
            commitmentReasons,
            "VND",
            new Coverage(sources.size() - cancelled, sources.size() - cancelled, 0)));

    BigDecimal cost = BigDecimal.ZERO;
    Set<String> costReasons = new TreeSet<>(common), ratioReasons = new TreeSet<>();
    Map<String, Set<String>> reasons = new LinkedHashMap<>();
    Map<String, Map<String, BigDecimal>> values = new LinkedHashMap<>();
    for (String key : COUNTS) {
      reasons.put(key, new TreeSet<>(common));
      values.put(key, new HashMap<>());
    }
    Set<String> postKeys = new HashSet<>(), periods = new HashSet<>();
    for (var source : eligible) {
      JsonNode data = source.payload, calc = source.calculation;
      try {
        if (!"VND".equals(data.path("currency").asText())) costReasons.add("UNSUPPORTED_CURRENCY");
        BigDecimal sum = BigDecimal.ZERO;
        if (!data.path("costs").isArray()
            || data.path("costs").isEmpty()
            || !data.path("financialConfirmed").asBoolean()) throw new IllegalArgumentException();
        for (var item : data.path("costs")) {
          BigDecimal amount = OutcomeFinance.amount(item.get("amount"));
          if (amount == null) throw new IllegalArgumentException();
          sum = sum.add(amount);
        }
        BigDecimal recorded = OutcomeFinance.amount(calc.get("actualCost"));
        if (recorded == null || sum.compareTo(recorded) != 0 || recorded.signum() <= 0)
          throw new IllegalArgumentException();
        cost = cost.add(recorded);
      } catch (RuntimeException e) {
        costReasons.add("INVALID_OR_MISSING_ACTUAL_COST");
      }
      if (!OutcomeFinance.POLICY.equals(calc.path("formulaVersion").asText()))
        ratioReasons.add("UNSUPPORTED_FINANCIAL_POLICY");
      if (!data.path("posts").isArray() || data.path("posts").isEmpty())
        COUNTS.forEach(k -> reasons.get(k).add("MISSING_POSTS"));
      for (var post : data.path("posts")) {
        String key =
            source.publicationKeys.get(
                post.path("deliverableId").asText()
                    + ":"
                    + post.path("submissionRevision").asLong());
        if (key == null) {
          COUNTS.forEach(k -> reasons.get(k).add("MISSING_PUBLICATION_REFERENCE"));
          continue;
        }
        postKeys.add(key);
        String period = period(post);
        if (period == null) COUNTS.forEach(k -> reasons.get(k).add("MISSING_MEASUREMENT_PERIOD"));
        else periods.add(period);
        if (period == null || !period.equals(period(data)))
          ratioReasons.add("INCONSISTENT_FINANCIAL_PERIOD");
        for (String field : COUNTS) {
          try {
            Long number = OutcomeFinance.metric(post.get(field));
            if (number == null) {
              reasons.get(field).add("MISSING_METRIC_INPUT");
              continue;
            }
            BigDecimal numberValue = BigDecimal.valueOf(number),
                old = values.get(field).putIfAbsent(key, numberValue);
            if (old != null && old.compareTo(numberValue) != 0)
              reasons.get(field).add("DUPLICATE_POST_CONFLICT");
          } catch (RuntimeException e) {
            reasons.get(field).add("INVALID_METRIC_INPUT");
          }
        }
      }
    }
    if (periods.size() > 1)
      COUNTS.forEach(k -> reasons.get(k).add("INCONSISTENT_MEASUREMENT_PERIOD"));
    Coverage coverage = new Coverage(completed.size(), eligible.size(), postKeys.size());
    result.put("actualCost", metric(cost, costReasons, "VND", coverage));
    for (String field : COUNTS)
      result.put(
          field,
          metric(
              values.get(field).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
              reasons.get(field),
              "COUNT",
              coverage));
    Set<String> engagementReasons = new TreeSet<>();
    BigDecimal engagements = BigDecimal.ZERO;
    for (String field : List.of("likes", "comments", "shares")) {
      engagementReasons.addAll(reasons.get(field));
      engagements =
          engagements.add(
              values.get(field).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }
    result.put("engagements", metric(engagements, engagementReasons, "COUNT", coverage));
    for (String field : List.of("gmv", "revenue")) {
      Set<String> r = new TreeSet<>(common);
      BigDecimal amount = null;
      if (completed.size() > 1) r.add("ATTRIBUTION_NOT_ESTABLISHED");
      if (eligible.size() == 1) {
        var source = eligible.getFirst();
        if (!"VND".equals(source.payload.path("currency").asText())) r.add("UNSUPPORTED_CURRENCY");
        try {
          amount = OutcomeFinance.amount(source.payload.get(field));
        } catch (RuntimeException e) {
          r.add("INVALID_FINANCIAL_INPUT");
        }
      }
      if (eligible.isEmpty() || eligible.stream().anyMatch(s -> !s.payload.hasNonNull(field)))
        r.add("MISSING_FINANCIAL_INPUT");
      result.put(field, metric(amount, r, "VND", coverage));
    }
    result.put(
        "roas",
        ratio(
            result.get("gmv"),
            result.get("actualCost"),
            ratioReasons,
            BigDecimal.ONE,
            "RATIO",
            coverage,
            false));
    result.put(
        "roi",
        ratio(
            result.get("revenue"),
            result.get("actualCost"),
            ratioReasons,
            BigDecimal.ONE,
            "RATIO",
            coverage,
            true));
    result.put(
        "cpm",
        ratio(
            result.get("actualCost"),
            result.get("views"),
            ratioReasons,
            BigDecimal.valueOf(1000),
            "VND_PER_1000_VIEWS",
            coverage,
            false));
    result.put(
        "cpe",
        ratio(
            result.get("actualCost"),
            result.get("engagements"),
            ratioReasons,
            BigDecimal.ONE,
            "VND_PER_ENGAGEMENT",
            coverage,
            false));
    return new Report(
        POLICY,
        OutcomeFinance.POLICY,
        "COMPLETED_ACCEPTED_REVIEWED",
        "SELF_REPORTED",
        completed.size(),
        sources.size() - completed.size() - cancelled,
        cancelled,
        eligible.size(),
        missing,
        result);
  }

  static String period(JsonNode data) {
    try {
      Instant start = Instant.parse(data.path("periodStart").asText()),
          end = Instant.parse(data.path("periodEnd").asText());
      return end.isBefore(start) ? null : start + "/" + end;
    } catch (RuntimeException e) {
      return null;
    }
  }

  static Metric metric(BigDecimal value, Set<String> reasons, String unit, Coverage coverage) {
    return new Metric(
        reasons.isEmpty() ? value : null,
        reasons.isEmpty() ? "AVAILABLE" : "UNAVAILABLE",
        List.copyOf(reasons),
        unit,
        coverage);
  }

  static Metric ratio(
      Metric numerator,
      Metric denominator,
      Set<String> extra,
      BigDecimal multiplier,
      String unit,
      Coverage coverage,
      boolean subtractCost) {
    var reasons = new TreeSet<>(extra);
    reasons.addAll(numerator.unavailableReasons());
    reasons.addAll(denominator.unavailableReasons());
    if (denominator.value() != null && denominator.value().signum() <= 0)
      reasons.add("INVALID_DENOMINATOR");
    BigDecimal value = null;
    if (reasons.isEmpty() && numerator.value() != null && denominator.value() != null)
      value =
          (subtractCost ? numerator.value().subtract(denominator.value()) : numerator.value())
              .multiply(multiplier)
              .divide(denominator.value(), 4, RoundingMode.HALF_UP);
    return metric(value, reasons, unit, coverage);
  }
}
