package com.influencermatch.backend.recommendation.engine.phase0.model;

import com.influencermatch.backend.recommendation.engine.phase0.enums.CampaignEligibility;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.enums.Phase0Status;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record NormalizedCandidateContext(
    UUID creatorId,
    UUID masterId,
    String platform,

    // Tách biệt hai chiều độc lập: Điều kiện Campaign vs Chất lượng dữ liệu
    CampaignEligibility campaignEligibility,
    ScoringReadiness scoringReadiness,

    List<String> eligibilityReasons, // Lý do Campaign: PLATFORM_MISMATCH, EXCLUDED_BY_FEEDBACK, ACCOUNT_INACTIVE
    List<String> dataQualityReasons,  // Lý do Dữ liệu: INVALID_METRICS, SHORT_WINDOW_INSUFFICIENT_POSTS

    // Điểm chuẩn hóa [0, 1) sẵn sàng bàn giao cho Phase 1
    double erNormalized,
    double vrNormalized,
    double stabNormalized,

    // Raw metrics đã tính
    double rawEr,
    double rawVr,
    double rawStab,

    // Metadata bằng chứng phục vụ Phase 5 tính Confidence
    ErMethod erMethod,
    int postCount,
    boolean stabilityEvidenceUnavailable, // Chuẩn hóa thuật ngữ: không có bằng chứng ổn định (không phải ước lượng từ dữ liệu)
    double evidenceReliabilityM2, // r_i2
    String calibrationVersion
) {

  /**
   * Alias tương thích ngược cho isStabImputed().
   */
  public boolean isStabImputed() {
    return stabilityEvidenceUnavailable;
  }

  /**
   * Constructor tương thích ngược với 16 tham số cũ.
   */
  public NormalizedCandidateContext(
      UUID creatorId,
      UUID masterId,
      String platform,
      Phase0Status status,
      List<String> exclusionReasons,
      double erNormalized,
      double vrNormalized,
      double stabNormalized,
      double rawEr,
      double rawVr,
      double rawStab,
      ErMethod erMethod,
      int postCount,
      boolean stabilityEvidenceUnavailable,
      double evidenceReliabilityM2,
      String calibrationVersion
  ) {
    this(
        creatorId,
        masterId,
        platform,
        status == Phase0Status.INELIGIBLE ? CampaignEligibility.INELIGIBLE : CampaignEligibility.ELIGIBLE,
        status == Phase0Status.ELIGIBLE ? ScoringReadiness.SCORE_READY : (status == Phase0Status.PENDING_DATA ? ScoringReadiness.PROVISIONAL : ScoringReadiness.INVALID_DATA),
        status == Phase0Status.INELIGIBLE ? (exclusionReasons != null ? exclusionReasons : List.of()) : List.of(),
        status != Phase0Status.INELIGIBLE ? (exclusionReasons != null ? exclusionReasons : List.of()) : List.of(),
        erNormalized,
        vrNormalized,
        stabNormalized,
        rawEr,
        rawVr,
        rawStab,
        erMethod,
        postCount,
        stabilityEvidenceUnavailable,
        evidenceReliabilityM2,
        calibrationVersion
    );
  }

  /**
   * Creator đáp ứng đầy đủ điều kiện nghiệp vụ của Campaign.
   */
  public boolean isCampaignEligible() {
    return campaignEligibility == CampaignEligibility.ELIGIBLE;
  }

  /**
   * Creator có đầy đủ cả 3 tính năng và bằng chứng đo lường để vào Bảng xếp hạng chính.
   */
  public boolean isScoreReady() {
    return scoringReadiness == ScoringReadiness.SCORE_READY;
  }

  /**
   * Creator thỏa điều kiện Campaign nhưng thiếu dữ liệu để tính ổn định (m < 2 bài hoặc views=0).
   */
  public boolean isProvisional() {
    return scoringReadiness == ScoringReadiness.PROVISIONAL;
  }

  /**
   * Creator có dữ liệu bị lỗi/âm (không phải vi phạm điều kiện Campaign).
   */
  public boolean isDataError() {
    return scoringReadiness == ScoringReadiness.INVALID_DATA;
  }

  // --- Hỗ trợ tương thích ngược cho các module cũ ---
  public Phase0Status status() {
    if (campaignEligibility == CampaignEligibility.INELIGIBLE) {
      return Phase0Status.INELIGIBLE;
    }
    if (scoringReadiness == ScoringReadiness.INVALID_DATA) {
      return Phase0Status.INELIGIBLE;
    }
    if (scoringReadiness == ScoringReadiness.PROVISIONAL || campaignEligibility == CampaignEligibility.PENDING_VERIFICATION) {
      return Phase0Status.PENDING_DATA;
    }
    return Phase0Status.ELIGIBLE;
  }

  public List<String> exclusionReasons() {
    List<String> combined = new ArrayList<>();
    if (eligibilityReasons != null) combined.addAll(eligibilityReasons);
    if (dataQualityReasons != null) combined.addAll(dataQualityReasons);
    return Collections.unmodifiableList(combined);
  }

  public boolean isEligible() {
    return isCampaignEligible() && isScoreReady();
  }

  public boolean isExcluded() {
    return campaignEligibility == CampaignEligibility.INELIGIBLE || scoringReadiness == ScoringReadiness.INVALID_DATA;
  }
}
