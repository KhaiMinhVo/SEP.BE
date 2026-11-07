package com.influencermatch.backend.recommendation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.LocalDateTime;
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
  private final ObjectMapper objectMapper;

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

      // 4. Ưu tiên truy vấn ứng viên ĐÃ CÓ TRONG SHORTLIST của Campaign
      boolean hasExcluded = !excludedIds.isEmpty();
      Collection<UUID> safeExcluded = hasExcluded ? excludedIds : Set.of(DUMMY_UUID);

      List<RecommendationCandidateProjection> rawCandidates =
          publicCreatorMetricRepository.findCandidatesByShortlist(
              campaignId,
              briefVectorStr,
              hasExcluded,
              safeExcluded
          );

      // Nếu Shortlist chưa có ai, fallback sang quét toàn bộ kho Discovery (cho phép khám phá rộng)
      if (rawCandidates.isEmpty()) {
        boolean hasPlatforms = campaign.getPlatforms() != null && !campaign.getPlatforms().isEmpty();
        Collection<String> safePlatforms = hasPlatforms ? campaign.getPlatforms() : List.of("TIKTOK");

        rawCandidates =
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
        log.info("Shortlist was empty. Retrieved {} raw candidates via pgvector search for campaign {}", rawCandidates.size(), campaignId);
      } else {
        log.info("Retrieved {} candidates from shortlist for campaign {}", rawCandidates.size(), campaignId);
      }

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

      // 7. Sắp xếp điểm FinalScore giảm dần và chọn Top N (hoặc toàn bộ Shortlist nếu nhỏ hơn limit)
      int effectiveLimit = limit > 0 ? Math.min(limit, 50) : 30;
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
   * Flow 6.1 Four-Memory Feature Scorer & Fixed-Ideal TOPSIS Engine:
   * Chấm điểm độc lập 4 tầng bộ nhớ (SM1..SM4) trên thang chuẩn hóa [0, 1] và
   * xếp hạng bằng thuật toán Fixed-Ideal TOPSIS theo trọng số mục tiêu Campaign (w_j).
   */
  public ScoreBreakdownDto calculateFourMemoryScores(
      RecommendationCandidateProjection cand, Campaign campaign, UUID brandProfileId) {

    // --- 1. Score M1 (Historical Reliability via Bayesian Beta-Binomial Smoothing) ---
    List<HistoricalCampaign> pastCampaigns = historicalCampaignRepository.findByCreatorId(cand.getCreatorId());
    if (pastCampaigns.isEmpty() && cand.getMasterId() != null) {
      pastCampaigns = historicalCampaignRepository.findByMasterId(cand.getMasterId());
    }

    double p0 = 0.50; // prior mean
    double kappa = 3.0; // prior strength
    int n = pastCampaigns.size();
    int s = 0;
    double kpiSum = 0.0;
    int kpiCount = 0;

    for (HistoricalCampaign hist : pastCampaigns) {
      if (hist.getOutcome() != null) {
        BigDecimal roi = hist.getOutcome().getRoi();
        if (roi != null && roi.doubleValue() >= 1.0) {
          s++;
        }
        if (roi != null) {
          double normRoi = Math.max(0.0, Math.min(1.0, roi.doubleValue() / 2.0));
          kpiSum += normRoi;
          kpiCount++;
        }
      } else {
        s++; // mặc định hoàn thành hợp tác nếu không có outcome fail
      }
    }

    double pHat = (n == 0) ? p0 : (s + kappa * p0) / (n + kappa);
    double kpiScore = (kpiCount > 0) ? (kpiSum / kpiCount) : p0;
    double revScore = p0; // fallback prior cho Brand Review

    // Trọng số nội bộ SM1 (Objective-aware: Bayesian 0.30, KPI 0.50, Review 0.20)
    double sm1Norm = (0.30 * pHat) + (0.50 * kpiScore) + (0.20 * revScore);
    double m1Score = sm1Norm * 100.0;

    // --- 2. Score M2 (Public Creator Intelligence: Equal-Weight Mean 1/3 ER* + 1/3 VIEW* + 1/3 STAB) ---
    // a. Normalized Engagement Rate (ER*) with K_ER = 0.05 (5.0%)
    double rawEr = 0.0;
    if (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0 && cand.getAvgLikes() != null) {
      rawEr = cand.getAvgLikes().doubleValue() / cand.getAvgViews().doubleValue();
    } else if (cand.getEngagementRate() != null) {
      rawEr = cand.getEngagementRate().doubleValue() / 100.0;
    }
    double kEr = 0.05;
    double erNorm = (rawEr > 0) ? (rawEr / (rawEr + kEr)) : 0.0;

    // b. Normalized View-to-Follower Ratio (VIEW*) with K_VR = 0.50
    double rawVr = 0.0;
    long followers = cand.getFollowers() != null ? cand.getFollowers() : 0L;
    double avgViews = cand.getAvgViews() != null ? cand.getAvgViews().doubleValue() : 0.0;
    if (followers > 0 && avgViews > 0) {
      rawVr = avgViews / followers;
    }
    double kVr = 0.50;
    double vrNorm = (rawVr > 0) ? (rawVr / (rawVr + kVr)) : 0.0;

    // c. View Stability (STAB = 1 / (1 + CV))
    double stabNorm = 0.70;
    if (cand.getAvgViews() != null && cand.getAvgViews().doubleValue() > 0) {
      stabNorm = 0.75;
    }

    // Equal-Weight Mean
    double sm2Norm = (erNorm + vrNorm + stabNorm) / 3.0;
    double m2Score = sm2Norm * 100.0;

    // --- 3. Score M3 (Current Campaign Context: Semantic Relevance Cosine (c + 1)/2) ---
    double rawCosine = cand.getSemanticScore() != null ? cand.getSemanticScore() : 0.50;
    double clampedCosine = Math.max(-1.0, Math.min(1.0, rawCosine));
    double sm3Norm = (clampedCosine + 1.0) / 2.0;
    double m3Score = sm3Norm * 100.0;

    // --- 4. Score M4 (Brand Context: Preference Coverage Tag Matching |B_b ∩ T_i| / |B_b|) ---
    List<String> campaignNiches = campaign.getNiches() != null ? campaign.getNiches() : Collections.emptyList();
    String candNiche = cand.getNiche() != null ? cand.getNiche().trim().toLowerCase() : "";
    String candCat = cand.getCategory() != null ? cand.getCategory().trim().toLowerCase() : "";

    double sm4Norm = 0.50;
    if (!campaignNiches.isEmpty()) {
      long matches = campaignNiches.stream()
          .filter(niche -> {
            String lowerN = niche.trim().toLowerCase();
            return lowerN.equalsIgnoreCase(candNiche)
                || lowerN.equalsIgnoreCase(candCat)
                || (!candNiche.isEmpty() && candNiche.contains(lowerN))
                || (!candCat.isEmpty() && candCat.contains(lowerN));
          })
          .count();
      sm4Norm = (double) matches / campaignNiches.size();
    }

    // Kết hợp BrandPattern nếu có
    List<BrandPattern> patterns = brandPatternRepository.findByBrandProfileId(brandProfileId);
    if (!patterns.isEmpty()) {
      BrandPattern pattern = patterns.get(0);
      if (pattern.getPlatform() != null && pattern.getPlatform().equalsIgnoreCase(cand.getPlatform())) {
        sm4Norm = Math.min(1.0, sm4Norm + 0.10);
      }
    }
    double m4Score = sm4Norm * 100.0;

    // --- 5. Context-Aware Dynamic Weights & Fixed-Ideal TOPSIS Ranking ---
    // Business Priority Weights theo Campaign Objective
    String objective = campaign.getObjective() != null ? campaign.getObjective().name() : "AWARENESS";
    double w1, w2, w3, w4;
    switch (objective) {
      case "CONVERSION" -> { w1 = 0.40; w2 = 0.15; w3 = 0.30; w4 = 0.15; }
      case "ENGAGEMENT" -> { w1 = 0.15; w2 = 0.35; w3 = 0.35; w4 = 0.15; }
      case "AWARENESS" -> { w1 = 0.10; w2 = 0.35; w3 = 0.40; w4 = 0.15; }
      default -> { w1 = 0.15; w2 = 0.35; w3 = 0.35; w4 = 0.15; }
    }

    // Fixed-Ideal TOPSIS: A+ = (1,1,1,1), A- = (0,0,0,0)
    double dPos = Math.sqrt(
        Math.pow(w1 * (1.0 - sm1Norm), 2) +
        Math.pow(w2 * (1.0 - sm2Norm), 2) +
        Math.pow(w3 * (1.0 - sm3Norm), 2) +
        Math.pow(w4 * (1.0 - sm4Norm), 2)
    );

    double dNeg = Math.sqrt(
        Math.pow(w1 * sm1Norm, 2) +
        Math.pow(w2 * sm2Norm, 2) +
        Math.pow(w3 * sm3Norm, 2) +
        Math.pow(w4 * sm4Norm, 2)
    );

    double denom = dPos + dNeg;
    double topsisCloseness = (denom > 1e-9) ? (dNeg / denom) : 0.50;
    double finalScore = topsisCloseness * 100.0;

    return ScoreBreakdownDto.builder()
        .m1Score(roundToTwoDecimals(m1Score))
        .m2Score(roundToTwoDecimals(m2Score))
        .m3Score(roundToTwoDecimals(m3Score))
        .m4Score(roundToTwoDecimals(m4Score))
        .followerScore(roundToTwoDecimals(vrNorm * 100.0))
        .engagementScore(roundToTwoDecimals(erNorm * 100.0))
        .semanticMatchScore(roundToTwoDecimals(m3Score))
        .nicheMatchScore(roundToTwoDecimals(m4Score))
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

  /**
   * Phase 4: Feedback Loop & Tiến hóa bối cảnh M3 (Negative Constraints).
   * Brand từ chối hoặc phản hồi về KOC -> Bổ sung vào excludedCreatorIds, lưu feedbackHistory,
   * tiến hóa phiên bản CampaignContext (v_N + 1), cập nhật M4 BrandPattern.
   * QUY TẮC AN TOÀN: Tuyệt đối không thay đổi ngân sách hoặc lĩnh vực của Campaign!
   */
  @Transactional
  public RecommendationFeedbackResponse processFeedback(
      UUID campaignId, RecommendationFeedbackRequest request) {
    Permissions.require(Permission.RUN_RECOMMENDATION);
    Campaign campaign = ownership.requireOwned(campaignId);

    String action = request.getAction() != null ? request.getAction().trim().toUpperCase() : "REJECT";
    UUID creatorId = request.getCreatorId();

    List<CampaignContext> contexts =
        campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId);

    int prevVersion = 1;
    CampaignContext activeContext = null;
    ObjectNode dataNode;

    if (!contexts.isEmpty()) {
      activeContext = contexts.get(0);
      prevVersion = activeContext.getContextVersion();
      JsonNode existingData = activeContext.getContextData();
      if (existingData instanceof ObjectNode) {
        dataNode = (ObjectNode) existingData.deepCopy();
      } else if (existingData != null) {
        dataNode = (ObjectNode) objectMapper.valueToTree(existingData);
      } else {
        dataNode = objectMapper.createObjectNode();
      }
    } else {
      dataNode = objectMapper.createObjectNode();
      dataNode.put("budgetMin", campaign.getBudgetMin());
      dataNode.put("budgetMax", campaign.getBudgetMax());
      if (campaign.getPlatforms() != null) {
        dataNode.set("platforms", objectMapper.valueToTree(campaign.getPlatforms()));
      }
      if (campaign.getNiches() != null) {
        dataNode.set("niches", objectMapper.valueToTree(campaign.getNiches()));
      }
    }

    // Đảm bảo mảng excludedCreatorIds tồn tại
    ArrayNode excludedArray;
    if (dataNode.has("excludedCreatorIds") && dataNode.get("excludedCreatorIds").isArray()) {
      excludedArray = (ArrayNode) dataNode.get("excludedCreatorIds");
    } else {
      excludedArray = objectMapper.createArrayNode();
      dataNode.set("excludedCreatorIds", excludedArray);
    }

    // Đảm bảo mảng feedbackHistory tồn tại
    ArrayNode feedbackArray;
    if (dataNode.has("feedbackHistory") && dataNode.get("feedbackHistory").isArray()) {
      feedbackArray = (ArrayNode) dataNode.get("feedbackHistory");
    } else {
      feedbackArray = objectMapper.createArrayNode();
      dataNode.set("feedbackHistory", feedbackArray);
    }

    // 1. Ghi nhận sở thích mềm vào feedbackHistory để M4 phân tích
    ObjectNode feedbackEntry = objectMapper.createObjectNode();
    feedbackEntry.put("creatorId", creatorId.toString());
    feedbackEntry.put("action", action);
    if (request.getReasonCode() != null) {
      feedbackEntry.put("reasonCode", request.getReasonCode());
    }
    if (request.getReasonNote() != null) {
      feedbackEntry.put("reasonNote", request.getReasonNote());
    }
    feedbackEntry.put("timestamp", LocalDateTime.now().toString());
    feedbackArray.add(feedbackEntry);

    // 2. Nếu REJECT: Bổ sung creatorId vào excludedCreatorIds nếu chưa có (Idempotent)
    if ("REJECT".equalsIgnoreCase(action)) {
      boolean alreadyPresent = false;
      for (JsonNode node : excludedArray) {
        if (creatorId.toString().equalsIgnoreCase(node.asText())) {
          alreadyPresent = true;
          break;
        }
      }
      if (!alreadyPresent) {
        excludedArray.add(creatorId.toString());
      }
    }

    // 3. Quy tắc an toàn: Tuyệt đối KHÔNG thay đổi campaign.budgetMax, budgetMin, hay niches
    // Campaign entity trên database hoàn toàn giữ nguyên giá trị ban đầu.

    // 4. Tiến hóa phiên bản: Lưu trữ bản ghi cũ (active = false), tạo bản ghi mới (contextVersion = v_N + 1)
    if (activeContext != null) {
      activeContext.setActive(false);
      activeContext.setArchivedAt(LocalDateTime.now());
      campaignContextRepository.save(activeContext);
    }

    int nextVersion = (activeContext != null) ? prevVersion + 1 : 1;
    CampaignContext newContext = CampaignContext.builder()
        .campaign(campaign)
        .contextVersion(nextVersion)
        .contextData(dataNode)
        .active(true)
        .build();
    campaignContextRepository.save(newContext);

    // 5. Ghi nhận mô thức dài hạn M4 (BrandPattern)
    if (campaign.getBrandProfile() != null && request.getReasonCode() != null) {
      try {
        UUID brandProfileId = campaign.getBrandProfile().getId();
        BrandPattern pattern = brandPatternRepository
            .findByBrandProfileIdAndPatternType(brandProfileId, "REJECTION_FEEDBACK")
            .orElseGet(() -> BrandPattern.builder()
                .brandProfile(campaign.getBrandProfile())
                .patternType("REJECTION_FEEDBACK")
                .confidence(BigDecimal.valueOf(0.5))
                .evidenceCount(0)
                .learnedAt(LocalDateTime.now())
                .build());

        pattern.setEvidenceCount(pattern.getEvidenceCount() + 1);
        pattern.setConfidence(BigDecimal.valueOf(Math.min(1.0, 0.5 + 0.05 * pattern.getEvidenceCount())));
        pattern.setLearnedAt(LocalDateTime.now());

        ObjectNode patternValue = (pattern.getPatternValue() instanceof ObjectNode)
            ? (ObjectNode) pattern.getPatternValue()
            : objectMapper.createObjectNode();
        int count = patternValue.has(request.getReasonCode()) ? patternValue.get(request.getReasonCode()).asInt() : 0;
        patternValue.put(request.getReasonCode(), count + 1);
        pattern.setPatternValue(patternValue);

        brandPatternRepository.save(pattern);
      } catch (Exception ex) {
        log.warn("Could not update M4 BrandPattern for brand: {}", ex.getMessage());
      }
    }

    log.info("Feedback processed for campaign {}: creator {}, action {}, new context version {}",
        campaignId, creatorId, action, nextVersion);

    return RecommendationFeedbackResponse.builder()
        .campaignId(campaignId)
        .creatorId(creatorId)
        .action(action)
        .reasonCode(request.getReasonCode())
        .previousContextVersion(prevVersion)
        .newContextVersion(nextVersion)
        .totalExcludedCount(excludedArray.size())
        .message(action.equals("REJECT")
            ? "Đã loại trừ ứng viên khỏi các lượt gợi ý sau. Bối cảnh M3 tiến hóa lên v" + nextVersion
            : "Đã ghi nhận phản hồi. Bối cảnh M3 cập nhật lên v" + nextVersion)
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
