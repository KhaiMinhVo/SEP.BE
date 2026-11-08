package com.influencermatch.backend.recommendation.engine.phase0;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.phase0.enums.CampaignEligibility;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Phase0Orchestrator {

  private final EligibilityChecker eligibilityChecker = new EligibilityChecker();
  private final EvidenceValidator evidenceValidator = new EvidenceValidator();
  private final PolicyNormalizer policyNormalizer = new PolicyNormalizer();

  public List<NormalizedCandidateContext> execute(
      List<RecommendationCandidateProjection> candidates,
      Campaign campaign,
      Set<UUID> excludedIds,
      Map<UUID, List<Post>> postsByCreator) {
    return execute(candidates, campaign, excludedIds, postsByCreator, EvidenceValidator.DEFAULT_SOURCE_ZONE);
  }

  public List<NormalizedCandidateContext> execute(
      List<RecommendationCandidateProjection> candidates,
      Campaign campaign,
      Set<UUID> excludedIds,
      Map<UUID, List<Post>> postsByCreator,
      java.time.ZoneId fallbackSourceZone) {

    List<NormalizedCandidateContext> results = new ArrayList<>();

    if (candidates == null || candidates.isEmpty()) {
      return results;
    }

    for (RecommendationCandidateProjection cand : candidates) {
      UUID creatorId = cand.getCreatorId();
      String platform = cand.getPlatform() != null ? cand.getPlatform().trim().toUpperCase() : "";

      // LUỒNG 1: Campaign Eligibility (Hard Constraints với Precedence INELIGIBLE > PENDING_PLATFORM > ELIGIBLE)
      var elig = eligibilityChecker.check(cand, campaign, excludedIds);
      if (elig.isExcluded()) {
        results.add(new NormalizedCandidateContext(
            creatorId, cand.getMasterId(), platform,
            CampaignEligibility.INELIGIBLE, ScoringReadiness.INVALID_DATA,
            elig.reasons(), List.of(),
            0.0, 0.0, Double.NaN, 0.0, 0.0, Double.NaN, null, 0, false, 0.0,
            ScoringPolicyConfig.CALIBRATION_VERSION
        ));
        continue;
      }

      if (elig.isPendingData()) {
        results.add(new NormalizedCandidateContext(
            creatorId, cand.getMasterId(), platform,
            CampaignEligibility.PENDING_VERIFICATION, ScoringReadiness.PROVISIONAL,
            elig.reasons(), List.of(),
            0.0, 0.0, Double.NaN, 0.0, 0.0, Double.NaN, null, 0, false, 0.0,
            ScoringPolicyConfig.CALIBRATION_VERSION
        ));
        continue;
      }

      // LUỒNG 2 & 3: Evidence Validation & Policy-Based Saturation Normalization với Error Isolation
      try {
        // LUỒNG 2: Evidence Validation (truyền timezone nguồn đã xác nhận)
        List<Post> posts = postsByCreator != null ? postsByCreator.get(creatorId) : null;
        var ev = evidenceValidator.evaluate(cand, posts, fallbackSourceZone);

        // Phân luồng xử lý trước bước chuẩn hóa:
        // 1. NOT_SCORABLE: Giữ nguyên trạng thái và mã lý do, không thực hiện chuẩn hóa, không gọi resolveKEr
        if (ev.scoringReadiness() == ScoringReadiness.NOT_SCORABLE) {
          results.add(new NormalizedCandidateContext(
              creatorId, cand.getMasterId(), platform,
              CampaignEligibility.ELIGIBLE, ScoringReadiness.NOT_SCORABLE,
              List.of(), ev.dataQualityReasons(),
              Double.NaN, Double.NaN, Double.NaN,
              ev.rawEr(), ev.rawVr(), ev.rawStab(),
              ev.erMethod(), ev.postCount(), ev.isStabImputed(), ev.evidenceReliabilityM2(),
              ScoringPolicyConfig.CALIBRATION_VERSION
          ));
          continue;
        }

        // 2. INVALID_DATA: Cách ly lỗi dữ liệu
        if (ev.scoringReadiness() == ScoringReadiness.INVALID_DATA) {
          results.add(new NormalizedCandidateContext(
              creatorId, cand.getMasterId(), platform,
              CampaignEligibility.ELIGIBLE, ScoringReadiness.INVALID_DATA,
              List.of(), ev.dataQualityReasons(),
              0.0, 0.0, Double.NaN,
              ev.rawEr(), ev.rawVr(), ev.rawStab(),
              ev.erMethod(), ev.postCount(), ev.isStabImputed(), ev.evidenceReliabilityM2(),
              ScoringPolicyConfig.CALIBRATION_VERSION
          ));
          continue;
        }

        // LUỒNG 3: Policy-Based Saturation Normalization (x / (x + K)) - Chỉ chạy cho SCORE_READY hoặc PROVISIONAL
        double kEr = policyNormalizer.resolveKEr(platform, ev.erMethod());
        double kVr = policyNormalizer.resolveKVr(platform);

        double erNorm = policyNormalizer.normalize(ev.rawEr(), kEr);
        double vrNorm = policyNormalizer.normalize(ev.rawVr(), kVr);
        double stabNorm = Double.isNaN(ev.rawStab()) ? Double.NaN : Math.max(0.0, Math.min(1.0, ev.rawStab()));

        results.add(new NormalizedCandidateContext(
            creatorId, cand.getMasterId(), platform,
            CampaignEligibility.ELIGIBLE, ev.scoringReadiness(),
            List.of(), ev.dataQualityReasons(),
            erNorm, vrNorm, stabNorm,
            ev.rawEr(), ev.rawVr(), ev.rawStab(),
            ev.erMethod(), ev.postCount(), ev.isStabImputed(), ev.evidenceReliabilityM2(),
            ScoringPolicyConfig.CALIBRATION_VERSION
        ));
      } catch (IllegalArgumentException ex) {
        // TÁCH BIỆT: Lỗi chất lượng dữ liệu (âm, ER vượt scale, dữ liệu lỗi)
        // Creator này KHÔNG vi phạm điều kiện Campaign (campaignEligibility vẫn là ELIGIBLE!)
        // Chỉ bị đánh dấu ScoringReadiness = INVALID_DATA
        results.add(new NormalizedCandidateContext(
            creatorId, cand.getMasterId(), platform,
            CampaignEligibility.ELIGIBLE, ScoringReadiness.INVALID_DATA,
            List.of(), List.of("DATA_QUALITY_ERROR: " + ex.getMessage()),
            0.0, 0.0, Double.NaN,
            0.0, 0.0, Double.NaN,
            null, 0, true, 0.0,
            ScoringPolicyConfig.CALIBRATION_VERSION
        ));
      }
      // Note: IllegalStateException (lỗi cấu hình hệ thống K <= 0) cố ý KHÔNG bắt
      // để dừng toàn bộ Run nếu hệ thống bị cấu hình sai.
    }

    return results;
  }
}
