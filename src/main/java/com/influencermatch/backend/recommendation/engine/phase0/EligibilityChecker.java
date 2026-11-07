package com.influencermatch.backend.recommendation.engine.phase0;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.engine.phase0.enums.Phase0Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class EligibilityChecker {

  public record Result(
      Phase0Status status,
      List<String> reasons,
      boolean outsideFollowers,
      boolean priceUnknown
  ) {
    public boolean eligible() {
      return status == Phase0Status.ELIGIBLE;
    }

    public boolean isExcluded() {
      return status == Phase0Status.INELIGIBLE;
    }

    public boolean isPendingData() {
      return status == Phase0Status.PENDING_DATA;
    }
  }

  public Result check(
      RecommendationCandidateProjection cand,
      Campaign campaign,
      Set<UUID> excludedIds) {

    List<String> reasons = new ArrayList<>();
    boolean isHardIneligible = false;
    boolean isPendingData = false;

    // 1. Hard Exclusion: KOC nằm trong danh sách loại trừ (Negative Constraints từ Feedback)
    if (excludedIds != null && excludedIds.contains(cand.getCreatorId())) {
      reasons.add("EXCLUDED_BY_FEEDBACK");
      isHardIneligible = true;
    }

    // 2. Platform Validation (Tri-state)
    String candPlatform = cand.getPlatform() != null ? cand.getPlatform().trim().toUpperCase() : "";
    if (candPlatform.isEmpty()) {
      reasons.add("MISSING_PLATFORM: Creator platform is unknown");
      isPendingData = true;
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

    // 3. Soft: Follower range (Không làm loại, chỉ đánh dấu nhãn cảnh báo)
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

    // 4. Soft: Giá (Chưa có giá -> PRICE_UNKNOWN, không loại)
    boolean priceUnknown = true;

    // Quy tắc ưu tiên nghiêm ngặt: INELIGIBLE > PENDING_DATA > ELIGIBLE
    Phase0Status status;
    if (isHardIneligible) {
      status = Phase0Status.INELIGIBLE;
    } else if (isPendingData) {
      status = Phase0Status.PENDING_DATA;
    } else {
      status = Phase0Status.ELIGIBLE;
    }

    return new Result(status, reasons, outsideFollowers, priceUnknown);
  }
}
