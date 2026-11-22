package com.influencermatch.backend.recommendation.engine.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Phase 2: M1 Historical Scoring Engine (R / T / K Architecture).
 *
 * Components:
 * - R: Historical Review Reputation (Bayesian smoothing on verified reviews).
 * - T: Timeliness (STRICT_ALL_REQUIRED_DELIVERABLES_ON_TIME).
 * - K: Raw attainment against the KPI committed in the accepted proposal.
 *
 * Target Weights: R = 0.50, T = 0.30, K = 0.20.
 *
 * Gate: Minimum 3 collaborations from at least 2 distinct brands within 24 months.
 * The composite is available only when the evidence gate and R/T/K are all available.
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
      String rAvailability,
      String tAvailability,
      String kAvailability,
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
          "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE",
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
      JsonNode evidence = h.getEvidenceValue();
      if (!"COLLABORATION_COMPLETION".equals(h.getEvidenceType())
          || evidence == null
          || !"collaboration-completion-evidence-v1".equals(
              evidence.path("evidenceSchemaVersion").asText())
          || !evidence.path("brandAccepted").asBoolean(false)
          || !evidence.path("outcomeRevision").canConvertToLong()) continue;

      LocalDateTime eventTime = h.getBusinessOccurredAt();
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
    boolean gatePassed = eligibleCount >= 3 && distinctBrands >= 2;
    if (!gatePassed) {
      warnings.add(String.format(
          Locale.ROOT,
          "M1_GATE_FAILED: Cần tối thiểu 3 hợp tác từ 2 thương hiệu khác nhau trong 24 tháng (Hiện có: %d hợp tác, %d thương hiệu).",
          eligibleCount, distinctBrands));

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
    String rAvailability = rScore == null ? "UNAVAILABLE" : "AVAILABLE";

    // 5. T is available only when every eligible collaboration has portal timestamp evidence.
    double tSum = 0.0;
    boolean allTimelinessAvailable = !eligible.isEmpty();

    for (HistoricalCampaign h : eligible) {
      JsonNode ev = h.getEvidenceValue();
      boolean allDeliverablesOnTime = true;
      JsonNode deliverables = ev.path("deliverables");
      if ("BRAND_MANAGED".equals(ev.path("managementMode").asText())
          || !deliverables.isArray()
          || deliverables.isEmpty()) {
        allTimelinessAvailable = false;
        continue;
      }
      for (JsonNode deliverable : deliverables) {
        LocalDateTime submittedAt = parseTimestamp(deliverable, "submittedAt");
        LocalDateTime dueAt = parseTimestamp(deliverable, "dueAt");
        if (!"CREATOR_PORTAL".equals(deliverable.path("submissionSource").asText())
            || submittedAt == null
            || dueAt == null) {
          allTimelinessAvailable = false;
          break;
        }
        if (submittedAt.isAfter(dueAt)) allDeliverablesOnTime = false;
      }
      if (allTimelinessAvailable) tSum += allDeliverablesOnTime ? 1.0 : 0.0;
    }

    Double tScore = allTimelinessAvailable ? tSum / eligibleCount : null;
    String tAvailability = tScore == null ? "UNAVAILABLE" : "AVAILABLE";
    if (tScore == null) warnings.add("T_UNAVAILABLE: Thiếu timestamp nộp Portal hoặc hạn bàn giao; dữ liệu Brand-managed không dùng để suy ra T.");

    // 6. K uses raw attainment; quality is retained as a warning, not blended with the prior.
    double rawKSum = 0.0;
    boolean allKAvailable = !eligible.isEmpty();
    boolean kNotApplicable = false;

    for (HistoricalCampaign h : eligible) {
      JsonNode ev = h.getEvidenceValue();
      String status = ev.path("kpiStatus").asText();
      if ("NOT_APPLICABLE".equals(status)) {
        kNotApplicable = true;
        allKAvailable = false;
        continue;
      }
      if (!"AVAILABLE".equals(status)
          || !ev.path("kpiActual").isNumber()
          || !ev.path("kpiTarget").isNumber()
          || ev.path("kpiTarget").asDouble() <= 0) {
        allKAvailable = false;
        continue;
      }
      rawKSum += Math.max(0.0, Math.min(1.0,
          ev.path("kpiActual").asDouble() / ev.path("kpiTarget").asDouble()));
      if (!"VERIFIED".equalsIgnoreCase(ev.path("kpiEvidenceQuality").asText()))
        warnings.add("K_SELF_REPORTED: KPI đạt được dùng giá trị raw; nguồn tự báo cáo không được xem là đã xác minh.");
    }

    Double kScore = allKAvailable ? rawKSum / eligibleCount : null;
    String kAvailability = kScore != null ? "AVAILABLE" : kNotApplicable ? "NOT_APPLICABLE" : "UNAVAILABLE";
    if (kScore == null && !kNotApplicable) warnings.add("K_UNAVAILABLE: KPI đã cam kết nhưng thiếu actual hợp lệ.");
    if (kNotApplicable) warnings.add("K_NOT_APPLICABLE: Có hợp tác không cam kết KPI; composite M1 không được tái chuẩn hóa.");

    // 7. Strict complete case: never renormalize missing components.
    if (!gatePassed || rScore == null || tScore == null || kScore == null) {
      if (rScore == null) warnings.add("R_UNAVAILABLE: Chưa có Final Review hợp lệ.");
      return new M1Result(
          DEFAULT_PRIOR_MEAN,
          null,
          "UNAVAILABLE",
          rScore, tScore, kScore,
          rAvailability, tAvailability, kAvailability,
          eligibleCount, distinctBrands, maxBrandShare,
          warnings
      );
    }

    double sm1Norm = (WEIGHT_R * rScore) + (WEIGHT_T * tScore) + (WEIGHT_K * kScore);
    sm1Norm = Math.max(0.0, Math.min(1.0, sm1Norm));
    double score100 = sm1Norm * 100.0;

    return new M1Result(
        sm1Norm,
        score100,
        "AVAILABLE",
        rScore,
        tScore,
        kScore,
        rAvailability,
        tAvailability,
        kAvailability,
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
