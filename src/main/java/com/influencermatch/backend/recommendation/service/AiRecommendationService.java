package com.influencermatch.backend.recommendation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.billing.service.SubscriptionService;
import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.brand.repository.BrandPatternRepository;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.CampaignContext;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.campaign.model.RecommendationRun;
import com.influencermatch.backend.campaign.repository.CampaignContextRepository;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.recommendation.dto.*;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiRecommendationService {

  private final SubscriptionService subscriptionService;
  private final CampaignOwnershipService ownership;
  private final CampaignContextRepository campaignContextRepository;
  private final PublicCreatorMetricRepository publicCreatorMetricRepository;
  private final HistoricalCampaignRepository historicalCampaignRepository;
  private final BrandPatternRepository brandPatternRepository;
  private final ShortlistItemRepository shortlistItemRepository;
  private final RecommendationRunRepository recommendationRunRepository;
  private final AiServiceClient aiServiceClient;

  private static final UUID DUMMY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  /**
   * Flow 3 Core: Thực thi AI Recommendation theo Four-Memory Framework.
   * Gồm: Two-Phase Quota Reservation -> Nạp 4 tầng bộ nhớ -> pgvector Search ->
   * Four-Memory Feature Scorer -> Master-aware Grouping -> XAI Reasoning Card.
   */
  @Transactional
  public RecommendationResponseDto recommendKocs(UUID campaignId, int limit) {
    Permissions.require(Permission.RUN_RECOMMENDATION);
    Campaign campaign = ownership.requireOwned(campaignId);
    UUID brandProfileId = campaign.getBrandProfile().getId();

    // 1. Two-Phase Quota Reservation (Đặt trước Quota)
    UUID subscriptionId = subscriptionService.reserveRecommendationQuota(brandProfileId);
    log.info("Reserved AI recommendation quota for brand {} with subscription {}", brandProfileId, subscriptionId);

    try {
      // 2. Nạp Bối cảnh M3 (CampaignContext) & trích xuất danh sách bị từ chối (Negative Constraints)
      List<CampaignContext> contexts =
          campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId);
      int contextVersion = 1;
      Set<UUID> excludedIds = new HashSet<>();

      if (!contexts.isEmpty()) {
        CampaignContext activeContext = contexts.get(0);
        contextVersion = activeContext.getContextVersion();
        JsonNode data = activeContext.getContextData();
        if (data != null && data.has("excludedCreatorIds") && data.get("excludedCreatorIds").isArray()) {
          for (JsonNode idNode : data.get("excludedCreatorIds")) {
            try {
              excludedIds.add(UUID.fromString(idNode.asText()));
            } catch (Exception ignored) {
            }
          }
        }
      }

      // 3. Chuẩn bị truy vấn ngữ nghĩa (Query Brief) & sinh Vector 768 chiều
      String queryBrief = buildQueryBrief(campaign);
      List<Float> embedding = aiServiceClient.getEmbedding(queryBrief);
      String briefVectorStr = formatVectorForPgvector(embedding);

      // 4. Truy vấn Hybrid Candidate Retrieval (50 ứng viên thô qua pgvector c.embedding)
      boolean hasExcluded = !excludedIds.isEmpty();
      Collection<UUID> safeExcluded = hasExcluded ? excludedIds : Set.of(DUMMY_UUID);

      boolean hasPlatforms = campaign.getPlatforms() != null && !campaign.getPlatforms().isEmpty();
      Collection<String> safePlatforms = hasPlatforms ? campaign.getPlatforms() : List.of("TIKTOK");

      List<RecommendationCandidateProjection> rawCandidates =
          publicCreatorMetricRepository.findTopCandidatesByEmbedding(
              briefVectorStr,
              hasExcluded,
              safeExcluded,
              hasPlatforms,
              safePlatforms,
              campaign.getFollowerMin(),
              campaign.getFollowerMax(),
              50
          );

      log.info("Retrieved {} raw candidates via pgvector search for campaign {}", rawCandidates.size(), campaignId);

      // 5. Four-Memory Feature Scorer
      List<ScoredCandidate> scoredList = new ArrayList<>();
      for (RecommendationCandidateProjection cand : rawCandidates) {
        ScoreBreakdownDto breakdown = calculateFourMemoryScores(cand, campaign, brandProfileId);
        scoredList.add(new ScoredCandidate(cand, breakdown));
      }

      // 6. Master-aware Grouping (Khử trùng lặp đa kênh - Mỗi KOC chỉ lấy 1 kênh điểm cao nhất)
      Map<String, ScoredCandidate> uniqueKocs = new LinkedHashMap<>();
      for (ScoredCandidate item : scoredList) {
        String masterKey = item.candidate.getMasterId() != null
            ? item.candidate.getMasterId().toString()
            : item.candidate.getCreatorId().toString();

        if (!uniqueKocs.containsKey(masterKey)) {
          uniqueKocs.put(masterKey, item);
        } else {
          ScoredCandidate existing = uniqueKocs.get(masterKey);
          if (item.breakdown.getFinalScore() > existing.breakdown.getFinalScore()) {
            uniqueKocs.put(masterKey, item);
          }
        }
      }

      // 7. Sắp xếp điểm FinalScore giảm dần và chọn Top N
      int effectiveLimit = limit > 0 ? Math.min(limit, 20) : 10;
      List<ScoredCandidate> topList =
          uniqueKocs.values().stream()
              .sorted(Comparator.comparingDouble((ScoredCandidate sc) -> sc.breakdown.getFinalScore()).reversed())
              .limit(effectiveLimit)
              .toList();

      // 8. Tích hợp XAI Engine (POST /api/v1/ai/explain kèm Circuit Breaker)
      List<CreatorRecommendationItemDto> recommendationItems = new ArrayList<>();
      for (ScoredCandidate item : topList) {
        ReasoningCardDto reasoning = fetchReasoningCard(item.candidate, campaign, item.breakdown);

        Optional<ShortlistItem> shortlistItemOpt =
            shortlistItemRepository.findByCampaignIdAndCreatorId(campaignId, item.candidate.getCreatorId());
        boolean inShortlist = shortlistItemOpt.isPresent() && shortlistItemOpt.get().getStatus() != ShortlistStatus.REMOVED;
        ShortlistStatus itemStatus = shortlistItemOpt.map(ShortlistItem::getStatus).orElse(null);

        recommendationItems.add(
            CreatorRecommendationItemDto.builder()
                .creatorId(item.candidate.getCreatorId())
                .masterId(item.candidate.getMasterId())
                .platform(item.candidate.getPlatform())
                .displayName(item.candidate.getDisplayName() != null ? item.candidate.getDisplayName() : item.candidate.getUserName())
                .userName(item.candidate.getUserName())
                .avatarUrl(item.candidate.getAvatarUrl())
                .followers(item.candidate.getFollowers())
                .avgViews(item.candidate.getAvgViews())
                .avgLikes(item.candidate.getAvgLikes())
                .engagementRate(item.candidate.getEngagementRate())
                .niche(item.candidate.getNiche())
                .category(item.candidate.getCategory())
                .creatorType(item.candidate.getCreatorType())
                .isClaimed(item.candidate.getMasterId() != null)
                .isInShortlist(inShortlist)
                .shortlistStatus(itemStatus)
                .finalScore(roundToTwoDecimals(item.breakdown.getFinalScore()))
                .scoreBreakdown(item.breakdown)
                .reasoning(reasoning)
                .build()
        );
      }

      // 9. Lưu vết RecommendationRun
      RecommendationRun run =
          recommendationRunRepository.save(
              RecommendationRun.builder()
                  .campaign(campaign)
                  .brandProfile(campaign.getBrandProfile())
                  .contextVersion(contextVersion)
                  .status("COMPLETED")
                  .candidatesCount(recommendationItems.size())
                  .queryBrief(queryBrief)
                  .build()
          );

      return RecommendationResponseDto.builder()
          .recommendationRunId(run.getId())
          .campaignId(campaignId)
          .contextVersion(contextVersion)
          .totalCandidates(recommendationItems.size())
          .recommendations(recommendationItems)
          .build();

    } catch (Exception e) {
      // Rollback hoàn trả Quota nếu có bất kỳ ngoại lệ nào xảy ra trong quá trình xử lý
      subscriptionService.releaseRecommendationQuota(subscriptionId);
      log.error("AI recommendation failed for campaign {}, rolled back quota: {}", campaignId, e.getMessage());
      if (e instanceof BusinessException) {
        throw e;
      }
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to run AI recommendation: " + e.getMessage());
    }
  }

  /**
   * Tính toán điểm 4 tầng bộ nhớ theo Single Source of Truth của Java Backend:
   * FinalScore = 0.50 * Score_M2 + 0.30 * Score_M1 + 0.20 * Score_M4
   */
  public ScoreBreakdownDto calculateFourMemoryScores(
      RecommendationCandidateProjection cand, Campaign campaign, UUID brandProfileId) {

    // --- 1. Score M2 (Public Creator Fit) ---
    // a. Follower Score (Hàm log10 phi đối xứng)
    long minFollowers = campaign.getFollowerMin() != null ? campaign.getFollowerMin() : 10_000L;
    long maxFollowers = campaign.getFollowerMax() != null ? campaign.getFollowerMax() : 500_000L;
    long candFollowers = cand.getFollowers() != null ? cand.getFollowers() : 0L;

    double followerScore;
    if (candFollowers >= minFollowers && candFollowers <= maxFollowers) {
      followerScore = 100.0;
    } else if (candFollowers < minFollowers) {
      double ordersUnder = Math.log10((double) minFollowers / Math.max(candFollowers, 1));
      followerScore = Math.max(0.0, 100.0 - (ordersUnder * 50.0));
    } else {
      double ordersOver = Math.log10((double) candFollowers / Math.max(maxFollowers, 1));
      followerScore = Math.max(60.0, 100.0 - (ordersOver * 30.0)); // Sàn 60 điểm khi vượt trần
    }

    // b. Engagement Score (Chuẩn hóa 5.0% cho video ngắn TikTok/Reels/Shorts)
    double effectiveEr = 0.0;
    if (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0 && cand.getAvgLikes() != null) {
      effectiveEr = (cand.getAvgLikes().doubleValue() / cand.getAvgViews().doubleValue()) * 100.0;
    } else if (cand.getEngagementRate() != null) {
      effectiveEr = cand.getEngagementRate().doubleValue();
    }

    boolean isShortVideo = "TIKTOK".equalsIgnoreCase(cand.getPlatform())
        || "INSTAGRAM".equalsIgnoreCase(cand.getPlatform())
        || "YOUTUBE".equalsIgnoreCase(cand.getPlatform());

    double benchmarkEr = isShortVideo ? 5.0 : 3.0;
    double engagementScore = Math.min(100.0, (effectiveEr / benchmarkEr) * 100.0);

    // c. Semantic Match Score (Min-Max Rescaling dải Cosine [0.30, 0.85] -> [0.0, 100.0])
    double rawCosine = cand.getSemanticScore() != null ? cand.getSemanticScore() : 0.50;
    double clampedCosine = Math.max(0.30, Math.min(0.85, rawCosine));
    double semanticMatchScore = ((clampedCosine - 0.30) / (0.85 - 0.30)) * 100.0;

    // d. Niche Match Score (So khớp 6 cấp độ động)
    double nicheMatchScore = 50.0;
    List<String> campaignNiches = campaign.getNiches() != null ? campaign.getNiches() : Collections.emptyList();
    String candNiche = cand.getNiche() != null ? cand.getNiche().toLowerCase() : "";
    String candCat = cand.getCategory() != null ? cand.getCategory().toLowerCase() : "";

    boolean exactMatch = campaignNiches.stream().anyMatch(n -> n.equalsIgnoreCase(candNiche));
    boolean catMatch = campaignNiches.stream().anyMatch(n -> n.equalsIgnoreCase(candCat) || candCat.contains(n.toLowerCase()));

    if (exactMatch) {
      nicheMatchScore = 100.0;
    } else if (catMatch) {
      nicheMatchScore = 85.0;
    } else if (!candNiche.isEmpty() && campaignNiches.stream().anyMatch(n -> candNiche.contains(n.toLowerCase()))) {
      nicheMatchScore = 75.0;
    }

    double m2Score = (0.20 * followerScore) + (0.25 * engagementScore) + (0.30 * semanticMatchScore) + (0.25 * nicheMatchScore);

    // --- 2. Score M1 (Historical Reliability) ---
    // TEST-05: KOC chưa từng có lịch sử tự động gán trung tính 50.0 điểm
    double m1Score = 50.0;
    List<HistoricalCampaign> pastCampaigns = historicalCampaignRepository.findByCreatorId(cand.getCreatorId());
    if (pastCampaigns.isEmpty() && cand.getMasterId() != null) {
      pastCampaigns = historicalCampaignRepository.findByMasterId(cand.getMasterId());
    }

    if (!pastCampaigns.isEmpty()) {
      double baseM1 = 60.0;
      int count = 0;
      for (HistoricalCampaign hist : pastCampaigns) {
        if (hist.getOutcome() != null && hist.getOutcome().getRoi() != null) {
          double roi = hist.getOutcome().getRoi().doubleValue();
          if (roi >= 1.5) baseM1 += 15.0;
          else if (roi >= 1.0) baseM1 += 8.0;
          count++;
        }
      }
      m1Score = Math.min(100.0, Math.max(30.0, baseM1));
    }

    // --- 3. Score M4 (Brand Preference Match) ---
    double m4Score = 50.0;
    List<BrandPattern> patterns = brandPatternRepository.findByBrandProfileId(brandProfileId);
    if (!patterns.isEmpty()) {
      BrandPattern pattern = patterns.get(0);
      if (pattern.getPreferredEngagementRate() != null) {
        double preferredEr = pattern.getPreferredEngagementRate().doubleValue();
        double diff = Math.abs(effectiveEr - preferredEr);
        m4Score = Math.max(0.0, 100.0 - (diff * 12.0));
      }
      if (pattern.getPlatform() != null && pattern.getPlatform().equalsIgnoreCase(cand.getPlatform())) {
        m4Score = Math.min(100.0, m4Score + 10.0);
      }
    }

    // --- 4. Final Weighted Score ---
    double finalScore = (0.50 * m2Score) + (0.30 * m1Score) + (0.20 * m4Score);

    return ScoreBreakdownDto.builder()
        .m2Score(roundToTwoDecimals(m2Score))
        .m1Score(roundToTwoDecimals(m1Score))
        .m4Score(roundToTwoDecimals(m4Score))
        .followerScore(roundToTwoDecimals(followerScore))
        .engagementScore(roundToTwoDecimals(engagementScore))
        .semanticMatchScore(roundToTwoDecimals(semanticMatchScore))
        .nicheMatchScore(roundToTwoDecimals(nicheMatchScore))
        .finalScore(roundToTwoDecimals(finalScore))
        .build();
  }

  private ReasoningCardDto fetchReasoningCard(
      RecommendationCandidateProjection cand, Campaign campaign, ScoreBreakdownDto breakdown) {

    double candEr = cand.getEngagementRate() != null ? cand.getEngagementRate().doubleValue() : 0.0;
    Double erViews = (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0 && cand.getAvgLikes() != null)
        ? (cand.getAvgLikes().doubleValue() / cand.getAvgViews().doubleValue()) * 100.0
        : null;

    ExplainRequestDto explainReq = ExplainRequestDto.builder()
        .creatorName(cand.getDisplayName() != null ? cand.getDisplayName() : cand.getUserName())
        .followers(cand.getFollowers() != null ? cand.getFollowers() : 0L)
        .engagementRate(candEr)
        .engagementRateViews(erViews)
        .platform(cand.getPlatform() != null ? cand.getPlatform() : "TIKTOK")
        .niche(cand.getNiche() != null ? cand.getNiche() : "general")
        .category(cand.getCategory() != null ? cand.getCategory() : "Lifestyle")
        .creatorType(cand.getCreatorType() != null ? cand.getCreatorType() : "KOC")
        .avgViews(cand.getAvgViews() != null ? cand.getAvgViews().doubleValue() : 0.0)
        .avgLikes(cand.getAvgLikes() != null ? cand.getAvgLikes().doubleValue() : 0.0)
        .similarityScore(cand.getSemanticScore() != null ? cand.getSemanticScore() : 0.50)
        .campaignBrief(campaign.getProductService() != null ? campaign.getProductService() : campaign.getName())
        .build();

    ExplainResponseDto response = aiServiceClient.getExplanation(explainReq);

    return ReasoningCardDto.builder()
        .summary(response.getSummary())
        .reasons(response.getReasons())
        .explanationAvailable(response.isExplanationAvailable())
        .build();
  }

  private String buildQueryBrief(Campaign c) {
    StringBuilder sb = new StringBuilder();
    if (c.getName() != null) sb.append(c.getName()).append(". ");
    if (c.getProductService() != null) sb.append(c.getProductService()).append(". ");
    if (c.getObjective() != null) sb.append("Mục tiêu: ").append(c.getObjective()).append(". ");
    if (c.getNiches() != null && !c.getNiches().isEmpty()) {
      sb.append("Lĩnh vực: ").append(String.join(", ", c.getNiches())).append(". ");
    }
    if (c.getContentType() != null) sb.append("Hình thức: ").append(c.getContentType()).append(". ");
    return sb.toString().trim();
  }

  private String formatVectorForPgvector(List<Float> vector) {
    if (vector == null || vector.isEmpty()) {
      // Default zero vector for 768 dimensions
      return "[" + Collections.nCopies(768, "0.0").stream().collect(Collectors.joining(",")) + "]";
    }
    return "[" + vector.stream().map(Object::toString).collect(Collectors.joining(",")) + "]";
  }

  private double roundToTwoDecimals(double val) {
    return Math.round(val * 100.0) / 100.0;
  }

  private record ScoredCandidate(RecommendationCandidateProjection candidate, ScoreBreakdownDto breakdown) {}
}
