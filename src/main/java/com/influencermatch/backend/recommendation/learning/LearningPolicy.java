package com.influencermatch.backend.recommendation.learning;

import java.time.*;
import java.util.*;

/** Pure versioned policy. Scores are descriptive indices, not calibrated probabilities. */
public final class LearningPolicy {
  public static final String VERSION = "four-memory-v3-bayesian-history";
  public static final String M4_TYPE = "BRAND_FIT_BAYES_V2";

  private LearningPolicy() {}

  public record Evidence(
      UUID id,
      UUID reviewId,
      UUID collaborationId,
      UUID brandId,
      UUID masterId,
      UUID creatorId,
      Instant reviewedAt,
      String objective,
      String platform,
      String niche,
      int rating,
      int fitRating,
      Boolean onTime,
      Double kpi) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Signal(
      Double score,
      int count,
      int brandCount,
      double originalWeight,
      double qualityFactor,
      double effectiveWeight,
      String unavailableReason) {
    @com.fasterxml.jackson.annotation.JsonProperty("available")
    public boolean available() {
      return score != null;
    }
  }

  public record M1(
      Double score,
      Signal review,
      Signal timeliness,
      Signal kpi,
      double support,
      String scope,
      List<UUID> reviewEvidence,
      List<UUID> timeEvidence,
      List<UUID> kpiEvidence) {}

  public record Cohort(
      String key,
      String scope,
      String platform,
      String niche,
      UUID masterId,
      Double score,
      int count,
      int masterCount,
      double support,
      List<UUID> evidenceIds) {}

  public record Snapshot(Instant asOf, Instant cutoff, List<Evidence> evidence) {
    public static Snapshot empty(Instant now) {
      return new Snapshot(now, LearningPolicy.cutoff(now), List.of());
    }

    public M1 m1(UUID master, UUID creator, String objective) {
      var rows =
          evidence.stream()
              .filter(
                  e ->
                      master != null
                          ? master.equals(e.masterId)
                          : e.masterId == null && creator.equals(e.creatorId))
              .toList();
      var r = signal(rows, .5, 1, 0);
      var times = rows.stream().filter(e -> e.onTime != null).toList();
      var kpis =
          rows.stream()
              .filter(e -> e.kpi != null && objective != null && objective.equals(e.objective))
              .toList();
      var t = signal(times, .3, 1, 1);
      var k = signal(kpis, .2, .5, 2);
      double total =
          r.score == null ? 0 : .5 + (t.score == null ? 0 : .3) + (k.score == null ? 0 : .1);
      r = effective(r, total);
      t = effective(t, total);
      k = effective(k, total);
      Double score = total == 0 ? null : weighted(r) + weighted(t) + weighted(k);
      double support = total == 0 ? 0 : support(r) + support(t) + support(k);
      return new M1(
          score,
          r,
          t,
          k,
          support,
          master == null ? "CHANNEL_ONLY" : "MASTER_GENERAL",
          ids(r.score == null ? List.of() : rows),
          ids(t.effectiveWeight == 0 ? List.of() : times),
          ids(k.effectiveWeight == 0 ? List.of() : kpis));
    }

    public List<Cohort> cohorts(UUID brand) {
      Map<String, List<Evidence>> groups = new TreeMap<>();
      for (var e : evidence)
        if (brand.equals(e.brandId)) {
          if (e.masterId != null)
            groups.computeIfAbsent("MASTER:" + e.masterId, k -> new ArrayList<>()).add(e);
          if (!normalize(e.platform).isEmpty()) {
            groups
                .computeIfAbsent("PLATFORM:" + normalize(e.platform), k -> new ArrayList<>())
                .add(e);
            if (!normalize(e.niche).isEmpty())
              groups
                  .computeIfAbsent(
                      "NICHE:" + normalize(e.platform) + ":" + normalize(e.niche),
                      k -> new ArrayList<>())
                  .add(e);
          }
        }
      var result = new ArrayList<Cohort>();
      groups.forEach(
          (key, rows) -> {
            var e = rows.getFirst();
            int masters =
                (int)
                    rows.stream()
                        .map(Evidence::masterId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count();
            boolean personal = key.startsWith("MASTER:");
            boolean available = personal ? rows.size() >= 3 : rows.size() >= 5 && masters >= 3;
            result.add(
                new Cohort(
                    key,
                    personal
                        ? "BRAND_MASTER"
                        : key.startsWith("NICHE:") ? "PLATFORM_NICHE" : "PLATFORM",
                    personal ? null : normalize(e.platform),
                    key.startsWith("NICHE:") ? normalize(e.niche) : null,
                    personal ? e.masterId : null,
                    available ? bayes(rows.stream().map(Evidence::fitRating).toList()) : null,
                    rows.size(),
                    masters,
                    rows.size() / (rows.size() + 2.0),
                    ids(rows)));
          });
      return List.copyOf(result);
    }

    public Cohort observedM4(UUID brand, UUID master, String platform, String niche) {
      var all = cohorts(brand);
      for (String key :
          List.of(
              "MASTER:" + master,
              "NICHE:" + normalize(platform) + ":" + normalize(niche),
              "PLATFORM:" + normalize(platform))) for (var c : all) if (c.key.equals(key)) return c;
      return null;
    }

    public Cohort m4(UUID brand, UUID master, String platform, String niche) {
      var all = cohorts(brand);
      for (String key :
          List.of(
              "MASTER:" + master,
              "NICHE:" + normalize(platform) + ":" + normalize(niche),
              "PLATFORM:" + normalize(platform)))
        for (var c : all) if (c.key.equals(key) && c.score != null) return c;
      return null;
    }
  }

  public static Snapshot snapshot(Instant now, Collection<Evidence> input) {
    var ids = new HashSet<UUID>();
    var collaborations = new HashSet<UUID>();
    var rows =
        input.stream()
            .sorted(Comparator.comparing(Evidence::reviewedAt).thenComparing(Evidence::reviewId))
            .filter(e -> !e.reviewedAt.isBefore(cutoff(now)) && !e.reviewedAt.isAfter(now))
            .filter(e -> e.rating >= 1 && e.rating <= 5 && e.fitRating >= 1 && e.fitRating <= 5)
            .filter(e -> e.kpi == null || Double.isFinite(e.kpi) && e.kpi >= 0 && e.kpi <= 1)
            .filter(e -> ids.add(e.reviewId) && collaborations.add(e.collaborationId))
            .toList();
    return new Snapshot(now, cutoff(now), rows);
  }

  public static Instant cutoff(Instant now) {
    return now.atZone(ZoneOffset.UTC).minusMonths(24).toInstant();
  }

  public static String normalize(String value) {
    return value == null
        ? ""
        : java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
  }

  public static double bayes(List<Integer> ratings) {
    if (ratings.isEmpty() || ratings.stream().anyMatch(r -> r < 1 || r > 5))
      throw new IllegalArgumentException("Ratings 1-5 required");
    return 100
        * (1 + ratings.stream().mapToDouble(r -> (r - 1) / 4.0).sum())
        / (2 + ratings.size());
  }

  private static Signal signal(List<Evidence> rows, double weight, double quality, int kind) {
    int brands = (int) rows.stream().map(Evidence::brandId).distinct().count();
    boolean available = rows.size() >= 3 && brands >= 2;
    Double score =
        !available
            ? null
            : kind == 0
                ? bayes(rows.stream().map(Evidence::rating).toList())
                : kind == 1
                    ? 100.0
                        * (1 + rows.stream().filter(e -> Boolean.TRUE.equals(e.onTime)).count())
                        / (2 + rows.size())
                    : 100 * rows.stream().mapToDouble(Evidence::kpi).average().orElseThrow();
    return new Signal(
        score,
        rows.size(),
        brands,
        weight,
        quality,
        0,
        available
            ? null
            : rows.size() < 3 ? "INSUFFICIENT_COLLABORATIONS" : "INSUFFICIENT_BRAND_DIVERSITY");
  }

  private static Signal effective(Signal s, double total) {
    return new Signal(
        s.score,
        s.count,
        s.brandCount,
        s.originalWeight,
        s.qualityFactor,
        total == 0 || s.score == null ? 0 : s.originalWeight * s.qualityFactor / total,
        s.unavailableReason);
  }

  private static double weighted(Signal s) {
    return s.score == null ? 0 : s.effectiveWeight * s.score;
  }

  private static double support(Signal s) {
    return s.score == null ? 0 : s.originalWeight * s.qualityFactor * s.count / (s.count + 2.0);
  }

  private static List<UUID> ids(List<Evidence> rows) {
    return rows.stream().map(Evidence::id).sorted().toList();
  }

  public static Map<String, Object> parameters() {
    var result =
        new LinkedHashMap<String, Object>(
            Map.of(
                "policy",
                VERSION,
                "ratingPrior",
                List.of(.4, .4, .4, .4, .4),
                "timelinessPrior",
                List.of(1, 1),
                "windowMonths",
                24,
                "minimumCollaborations",
                3,
                "minimumBrands",
                2,
                "m4PersonalMinimum",
                3,
                "m4CohortMinimum",
                5,
                "m4DistinctMasters",
                3,
                "kpiSelfReportedQuality",
                .5));
    result.put("m1Weights", Map.of("R", .5, "T", .3, "K", .2));
    result.put("topWeights", Map.of("M2", .5, "M1", .3, "M4", .2));
    result.put("ratingUtilities", List.of(0, .25, .5, .75, 1));
    result.put("normalization", "FIXED_0_100_AVAILABLE_WEIGHT_RENORMALIZATION");
    result.put("cohortPriority", List.of("BRAND_MASTER", "PLATFORM_NICHE", "PLATFORM"));
    return Map.copyOf(result);
  }
}
