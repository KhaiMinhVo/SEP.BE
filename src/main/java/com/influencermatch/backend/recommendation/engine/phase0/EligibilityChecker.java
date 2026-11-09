package com.influencermatch.backend.recommendation.engine.phase0;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.engine.phase0.enums.CampaignEligibility;
import com.influencermatch.backend.recommendation.engine.phase0.enums.Phase0Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class EligibilityChecker {

  public record Result(
      CampaignEligibility campaignEligibility,
      Phase0Status status,
      List<String> reasons,
      boolean outsideFollowers,
      boolean priceUnknown
  ) {
    public boolean eligible() {
      return campaignEligibility == CampaignEligibility.ELIGIBLE;
    }

    public boolean isExcluded() {
      return campaignEligibility == CampaignEligibility.INELIGIBLE;
    }

    public boolean isPendingData() {
      return campaignEligibility == CampaignEligibility.PENDING_VERIFICATION;
    }
  }

  public Result check(
      RecommendationCandidateProjection cand,
      Campaign campaign,
      Set<UUID> excludedIds) {

    List<String> reasons = new ArrayList<>();
    boolean isHardIneligible = false;
    boolean isPendingVerification = false;

    // 1. Hard Exclusion: KOC nằm trong danh sách loại trừ (Negative Constraints từ Feedback)
    if (excludedIds != null && excludedIds.contains(cand.getCreatorId())) {
      reasons.add("EXCLUDED_BY_FEEDBACK");
      isHardIneligible = true;
    }

    // 2. Account Status Validation (Ràng buộc trạng thái tài khoản)
    String accountStatus = cand.getAccountStatus() != null ? cand.getAccountStatus().trim().toUpperCase() : null;
    if (accountStatus == null || accountStatus.isEmpty() || "UNKNOWN".equals(accountStatus)) {
      reasons.add("UNKNOWN_ACCOUNT_STATUS: Creator account status is unknown");
      isPendingVerification = true;
    } else if ("SUSPENDED".equals(accountStatus) || "BANNED".equals(accountStatus) || "DELETED".equals(accountStatus) || "INACTIVE".equals(accountStatus)) {
      reasons.add("ACCOUNT_INACTIVE: Account status is " + accountStatus);
      isHardIneligible = true;
    } else if (!"ACTIVE".equals(accountStatus)) {
      reasons.add("UNRECOGNIZED_ACCOUNT_STATUS: " + accountStatus);
      isHardIneligible = true;
    }

    // 3. Platform Validation (Tri-state)
    String candPlatform = cand.getPlatform() != null ? cand.getPlatform().trim().toUpperCase() : "";
    if (candPlatform.isEmpty()) {
      reasons.add("MISSING_PLATFORM: Creator platform is unknown");
      isPendingVerification = true;
    } else {
      List<String> allowedPlatforms = (campaign != null && campaign.getPlatforms() != null)
          ? campaign.getPlatforms()
          : List.of();

      if (!allowedPlatforms.isEmpty()) {
        boolean match = allowedPlatforms.stream()
            .anyMatch(p -> p.trim().equalsIgnoreCase(candPlatform));
        if (!match) {
          reasons.add("PLATFORM_MISMATCH: " + candPlatform);
          isHardIneligible = true;
        }
      }
    }

    // 4. Soft: Follower range (Không làm loại, chỉ đánh dấu nhãn cảnh báo)
    boolean outsideFollowers = false;
    long followers = cand.getFollowers() != null ? cand.getFollowers() : 0L;
    if (campaign != null) {
      if (campaign.getFollowerMin() != null && followers < campaign.getFollowerMin()) {
        outsideFollowers = true;
      }
      if (campaign.getFollowerMax() != null && followers > campaign.getFollowerMax()) {
        outsideFollowers = true;
      }
    }

    // 5. Soft: Giá (Chưa có giá -> PRICE_UNKNOWN, không loại)
    boolean priceUnknown = true;

    // Quy tắc ưu tiên nghiêm ngặt cho Campaign: INELIGIBLE > PENDING_VERIFICATION > ELIGIBLE
    CampaignEligibility campaignEligibility;
    Phase0Status status;
    if (isHardIneligible) {
      campaignEligibility = CampaignEligibility.INELIGIBLE;
      status = Phase0Status.INELIGIBLE;
    } else if (isPendingVerification) {
      campaignEligibility = CampaignEligibility.PENDING_VERIFICATION;
      status = Phase0Status.PENDING_DATA;
    } else {
      campaignEligibility = CampaignEligibility.ELIGIBLE;
      status = Phase0Status.ELIGIBLE;
    }

    return new Result(campaignEligibility, status, reasons, outsideFollowers, priceUnknown);
  }
}
