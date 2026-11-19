package com.influencermatch.backend.recommendation.engine.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Phase 2: M1 Historical Scoring Engine (R / T / K Architecture).
 *
 * Components:
 * - R: Historical Review Reputation (Bayesian smoothing on verified reviews).
 * - T: Timeliness (STRICT_ALL_REQUIRED_DELIVERABLES_ON_TIME).
 * - K: KPI Attainment (attainment vs target with qK = 0.50 for SELF_REPORTED).
 *
 * Target Weights: R = 0.50, T = 0.30, K = 0.20.
 *
 * Gate: Minimum 3 collaborations from at least 2 distinct brands within 24 months.
 * If gate is not met: m1Availability = UNAVAILABLE, m1LearnedScore = null.
 */
public class M1HistoricalScorer {

  public static final double DEFAULT_PRIOR_MEAN = 0.50;
  public static final double DEFAULT_PRIOR_STRENGTH = 3.0;

  public static final double WEIGHT_R = 0.50;
  public static final double WEIGHT_T = 0.30;
  public static final double WEIGHT_K = 0.20;

  public record M1Result(
      double sm1Norm,                // [0, 1] for TOPSIS computation (anchor 0.50 if unavailable)
      Double m1LearnedScore,         // [0, 100] or null if UNAVAILABLE
      String m1Availability,         // AVAILABLE | UNAVAILABLE
      Double rScore,                 // [0, 1] or null
      Double tScore,                 // [0, 1] or null
      Double kScore,                 // [0, 1] or null
      int eligibleCollaborations,    // Count of eligible collaborations evaluated
      int distinctBrandCount,        // Distinct brands count
      double maxBrandShare,          // Ratio of most dominant brand [0, 1]
      List<String> warnings          // Quality and concentration warnings
  ) {
    public boolean isAvailable() {
      return "AVAILABLE".equals(m1Availability);
    }
  }

  public M1Result evaluate(
      List<HistoricalCampaign> pastCampaigns,
      String objective,
      LocalDateTime scoringAsOf) {

    if (scoringAsOf == null) {
      scoringAsOf = LocalDateTime.now();
    }
    LocalDateTime cutoff24m = scoringAsOf.minusMonths(24);

    if (pastCampaigns == null || pastCampaigns.isEmpty()) {
      return new M1Result(
          DEFAULT_PRIOR_MEAN,
          null,
          "UNAVAILABLE",
          null, null, null,
          0, 0, 0.0,
          List.of("M1_UNAVAILABLE: Chưa có lịch sử hợp tác nào được ghi nhận.")
      );
    }

    // 1. Filter active entries within 24-month window and deduplicate by Collaboration
    Map<String, HistoricalCampaign> uniqueCollabs = new LinkedHashMap<>();
    for (HistoricalCampaign h : pastCampaigns) {
      if (h == null) continue;
      if ("REVOKED".equalsIgnoreCase(h.getStatus())) continue;
      if (h.getRecordedAt() != null && h.getRecordedAt().isAfter(scoringAsOf)) continue;

      LocalDateTime eventTime = h.getBusinessOccurredAt() != null ? h.getBusinessOccurredAt() : h.getRecordedAt();
      if (eventTime == null || eventTime.isAfter(scoringAsOf) || eventTime.isBefore(cutoff24m)) continue;

      String key = (h.getCollaboration() != null && h.getCollaboration().getId() != null)
          ? "collab:" + h.getCollaboration().getId()
          : "hist:" + (h.getEvidenceRootId() != null ? h.getEvidenceRootId() : h.getId());

      uniqueCollabs.putIfAbsent(key, h);
    }

    List<HistoricalCampaign> eligible = new ArrayList<>(uniqueCollabs.values());
    int eligibleCount = eligible.size();

    // 2. Count distinct brands and calculate max brand share
    Map<String, Integer> brandCounts = new HashMap<>();
    for (HistoricalCampaign h : eligible) {
      String brandId = null;
      if (h.getCollaboration() != null
          && h.getCollaboration().getCampaign() != null
          && h.getCollaboration().getCampaign().getBrandProfile() != null
          && h.getCollaboration().getCampaign().getBrandProfile().getId() != null) {
        brandId = h.getCollaboration().getCampaign().getBrandProfile().getId().toString();
      } else if (h.getEvidenceValue() != null && h.getEvidenceValue().has("brandProfileId") && !h.getEvidenceValue().get("brandProfileId").isNull()) {
        brandId = h.getEvidenceValue().get("brandProfileId").asText();
      }

      if (brandId != null && !brandId.isBlank()) {
        brandCounts.put(brandId, brandCounts.getOrDefault(brandId, 0) + 1);
      }
    }

    int distinctBrands = brandCounts.size();
    int maxCollabFromSingleBrand = brandCounts.values().stream().mapToInt(v -> v).max().orElse(0);
    double maxBrandShare = (eligibleCount > 0) ? ((double) maxCollabFromSingleBrand / eligibleCount) : 0.0;

    List<String> warnings = new ArrayList<>();
    if (maxBrandShare > 0.80 && eligibleCount >= 3) {
      warnings.add(String.format(
          Locale.ROOT,
          "HIGH_BRAND_CONCENTRATION_RISK: Một thương hiệu chiếm %.1f%% tổng số hợp tác (> 80%%).",
          maxBrandShare * 100.0));
    }

    // 3. Minimum Evidence Gate: >= 3 Collaborations from >= 2 Brands
    boolean gatePassed = (eligibleCount >= 3 && distinctBrands >= 2);
    if (!gatePassed) {
      warnings.add(String.format(
          Locale.ROOT,
          "M1_GATE_FAILED: Cần tối thiểu 3 hợp tác từ 2 thương hiệu khác nhau trong 24 tháng (Hiện có: %d hợp tác, %d thương hiệu).",
          eligibleCount, distinctBrands));

      return new M1Result(
          DEFAULT_PRIOR_MEAN,
          null,
          "UNAVAILABLE",
          null, null, null,
          eligibleCount, distinctBrands, maxBrandShare,
          warnings
      );
    }

    // 4. Component R: Historical Review Reputation
    int rSampleCount = 0;
    int rSuccessCount = 0;
    double ratingSumNorm = 0.0;
    int ratingCount = 0;

    for (HistoricalCampaign h : eligible) {
      double rating = -1.0;
      boolean wouldAgain = false;

      if (h.getEvidenceValue() != null && !h.getEvidenceValue().isNull()) {
        JsonNode ev = h.getEvidenceValue();
        if (ev.has("overallRating") && !ev.get("overallRating").isNull()) {
          rating = ev.get("overallRating").asDouble();
        }
        if (ev.has("wouldCollaborateAgain") && !ev.get("wouldCollaborateAgain").isNull()) {
          wouldAgain = ev.get("wouldCollaborateAgain").asBoolean();
        }
      }

      if (rating >= 1.0 && rating <= 5.0) {
        rSampleCount++;
        ratingSumNorm += (rating - 1.0) / 4.0; // Normalized to [0, 1]
        ratingCount++;

        if (rating >= 4.0 || wouldAgain) {
          rSuccessCount++;
        }
      }
    }

    Double rScore = null;
    if (rSampleCount > 0) {
      double pHat = (rSuccessCount + DEFAULT_PRIOR_STRENGTH * DEFAULT_PRIOR_MEAN) / (rSampleCount + DEFAULT_PRIOR_STRENGTH);
      double avgRatingNorm = (ratingCount > 0) ? (ratingSumNorm / ratingCount) : pHat;
      rScore = Math.max(0.0, Math.min(1.0, 0.60 * pHat + 0.40 * avgRatingNorm));
    }

    // 5. Component T: Timeliness (STRICT_ALL_REQUIRED_DELIVERABLES_ON_TIME)
    double tSum = 0.0;
    int tCount = 0;
    boolean hasUnreliableTimeliness = false;

    for (HistoricalCampaign h : eligible) {
      Boolean collabOnTime = null;
      JsonNode ev = h.getEvidenceValue();

      if (ev != null && !ev.isNull()) {
        if (ev.has("deliverables") && ev.get("deliverables").isArray() && !ev.get("deliverables").isEmpty()) {
          boolean allDelivOnTime = true;
          boolean deliverableFound = false;

          for (JsonNode deliv : ev.get("deliverables")) {
            deliverableFound = true;
            LocalDateTime submittedAt = parseTimestamp(deliv, "submittedAt");
            LocalDateTime approvedRevisionDeadline = parseTimestamp(deliv, "approvedRevisionDeadline");
            LocalDateTime initialDueDate = parseTimestamp(deliv, "initialDueDate");
            if (initialDueDate == null) {
              initialDueDate = parseTimestamp(deliv, "dueAt");
            }
            if (initialDueDate == null) {
              initialDueDate = parseTimestamp(deliv, "deadline");
            }

            LocalDateTime effectiveDeadline = approvedRevisionDeadline != null ? approvedRevisionDeadline : initialDueDate;

            if (submittedAt == null || effectiveDeadline == null) {
              hasUnreliableTimeliness = true;
              allDelivOnTime = false;
              break;
            }

            if (submittedAt.isAfter(effectiveDeadline)) {
              allDelivOnTime = false;
            }
          }

          if (deliverableFound && !hasUnreliableTimeliness) {
            collabOnTime = allDelivOnTime;
          }
        } else if (ev.has("initialDueDate") || ev.has("approvedRevisionDeadline") || ev.has("submittedAt")) {
          LocalDateTime submittedAt = parseTimestamp(ev, "submittedAt");
          LocalDateTime approvedRevisionDeadline = parseTimestamp(ev, "approvedRevisionDeadline");
          LocalDateTime initialDueDate = parseTimestamp(ev, "initialDueDate");
          if (initialDueDate == null) {
            initialDueDate = parseTimestamp(ev, "dueAt");
          }
          if (initialDueDate == null) {
            initialDueDate = parseTimestamp(ev, "deadline");
          }
          LocalDateTime effectiveDeadline = approvedRevisionDeadline != null ? approvedRevisionDeadline : initialDueDate;

          if (submittedAt == null || effectiveDeadline == null) {
            hasUnreliableTimeliness = true;
          } else {
            collabOnTime = !submittedAt.isAfter(effectiveDeadline);
          }
        } else if (ev.has("allDeliverablesOnTime")) {
          collabOnTime = ev.get("allDeliverablesOnTime").asBoolean();
        } else if (ev.has("allOnTime")) {
          collabOnTime = ev.get("allOnTime").asBoolean();
        } else if (ev.has("delays") || ev.has("delayDays")) {
          int delays = ev.has("delays") ? ev.get("delays").asInt() : ev.get("delayDays").asInt();
          collabOnTime = (delays == 0);
        } else if (ev.has("onTime")) {
          collabOnTime = ev.get("onTime").asBoolean();
        } else {
          hasUnreliableTimeliness = true;
        }
      } else {
        hasUnreliableTimeliness = true;
      }

      if (collabOnTime != null && !hasUnreliableTimeliness) {
        tSum += collabOnTime ? 1.0 : 0.0;
        tCount++;
      }
    }

    Double tScore = (!hasUnreliableTimeliness && tCount == eligibleCount && tCount > 0) ? (tSum / tCount) : null;

    // 6. Component K: KPI Attainment
    double rawKSum = 0.0;
    double effectiveKSum = 0.0;
    int kCount = 0;

    for (HistoricalCampaign h : eligible) {
      // Check for pending measurement: skip from sample, do NOT treat as 0 or failure
      boolean isPending = false;
      if (h.getEvidenceValue() != null) {
        JsonNode ev = h.getEvidenceValue();
        if (ev.path("measurementPending").asBoolean(false)
            || "MEASUREMENT_PENDING".equalsIgnoreCase(ev.path("kpiStatus").asText())
            || "PENDING".equalsIgnoreCase(ev.path("kpiStatus").asText())
            || "MEASUREMENT_PENDING".equalsIgnoreCase(ev.path("status").asText())) {
          isPending = true;
        }
      }
      if (isPending) {
        continue;
      }

      Double rawAttainment = null;
      double qK = "VERIFIED".equalsIgnoreCase(h.getQuality()) ? 1.00 : 0.50; // 0.50 for SELF_REPORTED

      if (h.getOutcome() != null) {
        CampaignOutcome o = h.getOutcome();
        if (o.getRoi() != null) {
          rawAttainment = Math.max(0.0, Math.min(1.0, o.getRoi().doubleValue() / 2.0));
        } else if (o.getKpiTarget() != null && o.getKpiActual() != null && o.getKpiTarget().doubleValue() > 0) {
          double actual = o.getKpiActual().doubleValue();
          double target = o.getKpiTarget().doubleValue();
          rawAttainment = Math.max(0.0, Math.min(1.0, actual / target));
        }
      }

      if (rawAttainment == null && h.getEvidenceValue() != null && !h.getEvidenceValue().isNull()) {
        JsonNode ev = h.getEvidenceValue();
        if (ev.has("kpiAttainment") && !ev.get("kpiAttainment").isNull()) {
          rawAttainment = Math.max(0.0, Math.min(1.0, ev.get("kpiAttainment").asDouble()));
        } else if (ev.has("kpiActual") && ev.has("kpiTarget") && ev.get("kpiTarget").asDouble() > 0) {
          rawAttainment = Math.max(0.0, Math.min(1.0, ev.get("kpiActual").asDouble() / ev.get("kpiTarget").asDouble()));
        }
      }

      if (rawAttainment != null) {
        rawKSum += rawAttainment;
        double adjustedK = (qK * rawAttainment) + ((1.0 - qK) * DEFAULT_PRIOR_MEAN);
        effectiveKSum += adjustedK;
        kCount++;
      }
    }

    Double kScore = (kCount > 0) ? (rawKSum / kCount) : null;
    Double effectiveK = (kCount > 0) ? (effectiveKSum / kCount) : null;

    // 7. Aggregation with Availability Adjustment strictly adhering to fixed weights 0.50 / 0.30 / 0.20
    double sm1Norm;
    if (rScore != null && tScore != null && effectiveK != null) {
      sm1Norm = (WEIGHT_R * rScore) + (WEIGHT_T * tScore) + (WEIGHT_K * effectiveK);
    } else if (rScore != null && tScore != null) {
      sm1Norm = ((WEIGHT_R * rScore) + (WEIGHT_T * tScore)) / (WEIGHT_R + WEIGHT_T);
    } else if (rScore != null && effectiveK != null) {
      sm1Norm = ((WEIGHT_R * rScore) + (WEIGHT_K * effectiveK)) / (WEIGHT_R + WEIGHT_K);
    } else if (tScore != null && effectiveK != null) {
      sm1Norm = ((WEIGHT_T * tScore) + (WEIGHT_K * effectiveK)) / (WEIGHT_T + WEIGHT_K);
    } else if (rScore != null) {
      sm1Norm = rScore;
    } else if (tScore != null) {
      sm1Norm = tScore;
    } else if (effectiveK != null) {
      sm1Norm = effectiveK;
    } else {
      return new M1Result(
          DEFAULT_PRIOR_MEAN,
          null,
          "UNAVAILABLE",
          null, null, null,
          eligibleCount, distinctBrands, maxBrandShare,
          List.of("M1_UNAVAILABLE: Tất cả các thành phần R, T, K đều không khả dụng.")
      );
    }

    sm1Norm = Math.max(0.0, Math.min(1.0, sm1Norm));
    double score100 = sm1Norm * 100.0;

    return new M1Result(
        sm1Norm,
        score100,
        "AVAILABLE",
        rScore,
        tScore,
        kScore,
        eligibleCount,
        distinctBrands,
        maxBrandShare,
        warnings
    );
  }

  private LocalDateTime parseTimestamp(JsonNode node, String field) {
    if (node == null || !node.has(field) || node.get(field).isNull()) return null;
    String text = node.get(field).asText().trim();
    if (text.isEmpty() || "null".equalsIgnoreCase(text)) return null;
    try {
      return LocalDateTime.parse(text);
    } catch (Exception e) {
      try {
        return java.time.Instant.parse(text).atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
      } catch (Exception ex) {
        return null;
      }
    }
  }
}
