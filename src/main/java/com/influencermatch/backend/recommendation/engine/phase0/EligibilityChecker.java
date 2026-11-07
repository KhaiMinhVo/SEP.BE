package com.influencermatch.backend.recommendation.engine.phase0;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class EligibilityChecker {

  public record Result(boolean eligible, List<String> reasons, boolean outsideFollowers, boolean priceUnknown) {}

  public Result check(
      RecommendationCandidateProjection cand,
      Campaign campaign,
      Set<UUID> excludedIds) {

    List<String> reasons = new ArrayList<>();

    // 1. Hard: KOC nằm trong danh sách loại trừ (Negative Constraints)
    if (excludedIds != null && excludedIds.contains(cand.getCreatorId())) {
      reasons.add("EXCLUDED_BY_FEEDBACK");
    }

    // 2. Hard: Nền tảng (Platform Match)
    String candPlatform = cand.getPlatform() != null ? cand.getPlatform().trim().toUpperCase() : "";
    List<String> allowedPlatforms = campaign.getPlatforms() != null ? campaign.getPlatforms() : List.of();
    if (!allowedPlatforms.isEmpty()) {
      boolean match = allowedPlatforms.stream()
          .anyMatch(p -> p.trim().equalsIgnoreCase(candPlatform));
      if (!match) {
        reasons.add("PLATFORM_MISMATCH: " + candPlatform);
      }
    }

    // 3. Soft: Follower range (Không làm loại, chỉ đánh dấu nhãn)
    boolean outsideFollowers = false;
    long followers = cand.getFollowers() != null ? cand.getFollowers() : 0L;
    if (campaign.getFollowerMin() != null && followers < campaign.getFollowerMin()) {
      outsideFollowers = true;
    }
    if (campaign.getFollowerMax() != null && followers > campaign.getFollowerMax()) {
      outsideFollowers = true;
    }

    // 4. Soft: Giá (Chưa có giá -> PRICE_UNKNOWN, không loại)
    boolean priceUnknown = true;

    boolean eligible = reasons.isEmpty();
    return new Result(eligible, reasons, outsideFollowers, priceUnknown);
  }
}
