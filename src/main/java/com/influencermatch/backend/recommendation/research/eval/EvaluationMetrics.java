package com.influencermatch.backend.recommendation.research.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EvaluationMetrics {

  public static final String METRIC_VERSION = "metrics.v2";

  private EvaluationMetrics() {}

  public static double ndcgAtK(List<UUID> rankedIds, Map<UUID, Double> oracleUtilityById, int k) {
    if (k <= 0 || rankedIds == null || rankedIds.isEmpty() || oracleUtilityById == null || oracleUtilityById.isEmpty()) {
      return 0.0;
    }
    int limit = Math.min(k, rankedIds.size());
    double dcg = 0.0;
    for (int i = 0; i < limit; i++) {
      double rel = Math.max(0.0, oracleUtilityById.getOrDefault(rankedIds.get(i), 0.0));
      dcg += (Math.pow(2.0, rel) - 1.0) / (Math.log(i + 2.0) / Math.log(2.0));
    }

    List<Double> sortedRels = oracleUtilityById.values().stream()
        .map(v -> Math.max(0.0, v))
        .sorted(Comparator.reverseOrder())
        .limit(k)
        .toList();
    double idcg = 0.0;
    for (int i = 0; i < sortedRels.size(); i++) {
      idcg += (Math.pow(2.0, sortedRels.get(i)) - 1.0) / (Math.log(i + 2.0) / Math.log(2.0));
    }
    if (idcg <= 0.0) {
      return 0.0;
    }
    return dcg / idcg;
  }

  public static double expectedUtilityRegretAtK(
      List<UUID> rankedIds, Map<UUID, Double> oracleUtilityById, int k) {
    if (k <= 0 || oracleUtilityById == null || oracleUtilityById.isEmpty()) {
      return 0.0;
    }
    int denom = Math.min(k, oracleUtilityById.size());
    double oracleTopKSum = oracleUtilityById.values().stream()
        .sorted(Comparator.reverseOrder())
        .limit(denom)
        .mapToDouble(Double::doubleValue)
        .sum();
    double selectedSum = (rankedIds == null)
        ? 0.0
        : rankedIds.stream().limit(denom)
            .mapToDouble(id -> oracleUtilityById.getOrDefault(id, 0.0))
            .sum();
    return (oracleTopKSum - selectedSum) / denom;
  }

  public static double candidateScoringCoverage(int scoredCount, int eligibleUniverseCount) {
    if (eligibleUniverseCount <= 0) {
      return 0.0;
    }
    return (double) scoredCount / eligibleUniverseCount;
  }

  public static double listFillRateAtK(int rankedCount, int k) {
    if (k <= 0) {
      return 0.0;
    }
    return Math.min(k, Math.max(0, rankedCount)) / (double) k;
  }

  public static List<UUID> padShortListToK(List<UUID> rankedIds, int k) {
    List<UUID> out = new ArrayList<>(k);
    if (rankedIds != null) {
      rankedIds.stream().limit(k).forEach(out::add);
    }
    while (out.size() < k) {
      out.add(null);
    }
    return out;
  }
}
