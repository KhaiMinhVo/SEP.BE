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

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.brand.repository.BrandContextRepository brandContexts;

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.brand.service.BrandLearnedPatterns learned;

  private static final UUID DUMMY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  /**
   * Flow 3 Core: Thực thi AI Recommendation theo Four-Memory Framework. Gồm: Two-Phase Quota
   * Reservation -> Nạp 4 tầng bộ nhớ -> pgvector Search -> Four-Memory Feature Scorer ->
   * Master-aware Grouping -> XAI Reasoning Card.
   */
  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.campaign.service.CampaignM3ContextService m3;

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore m3Embeddings;

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.campaign.service.CampaignWriteLock campaignLocks;

  @Transactional
  public RecommendationResponseDto recommendKocs(UUID campaignId, int limit) {
    return recommendKocs(
        campaignId, limit, com.influencermatch.backend.campaign.enums.CandidateSource.SHORTLIST);
  }

  @Transactional
  public RecommendationResponseDto recommendKocs(
      UUID campaignId,
      int limit,
      com.influencermatch.backend.campaign.enums.CandidateSource source) {
    Permissions.require(Permission.RUN_RECOMMENDATION);
    Campaign campaign = ownership.requireOwned(campaignId);
    var campaignContext = m3.prepare(campaignId);
    var prepared = m3Embeddings.ready(campaignContext.getEmbeddingSourceContextId());
    if (prepared == null)
      throw new com.influencermatch.backend.exception.CampaignEmbeddingPendingException(
          m3Embeddings.view(campaignContext.getEmbeddingSourceContextId()).status());
    UUID brandProfileId = campaign.getBrandProfile().getId();
    var brandContext =
        brandContexts
            .findByBrandProfileId(brandProfileId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.BRAND_NOT_FOUND, "Brand context missing"));
    var learnedSnapshot = learned.snapshot(brandProfileId);

    UUID subscriptionId = null;
    try {
      // 2. Nạp Bối cảnh M3 (CampaignContext) & trích xuất danh sách bị từ chối (Negative
      // Constraints)
      int contextVersion = campaignContext.getContextVersion();
      Set<UUID> excludedIds = new HashSet<>();
      var excluded = campaignContext.getContextData().path("excludedCreatorIds");
      if (excluded.isArray())
        for (var item : excluded) {
          try {
            excludedIds.add(UUID.fromString(item.asText()));
          } catch (IllegalArgumentException ignored) {
          }
        }
      // 3. Chuẩn bị truy vấn ngữ nghĩa (Query Brief) & sinh Vector 768 chiều
      String queryBrief = prepared.text();
      String briefVectorStr = prepared.vector();
      // Candidate source is explicit; an empty Shortlist never broadens to Discovery.
      boolean hasExcluded = !excludedIds.isEmpty();
      Collection<UUID> safeExcluded = hasExcluded ? excludedIds : Set.of(DUMMY_UUID);

      List<RecommendationCandidateProjection> rawCandidates = new ArrayList<>();
      if (source == com.influencermatch.backend.campaign.enums.CandidateSource.SHORTLIST)
        rawCandidates =
            publicCreatorMetricRepository.findCandidatesByShortlist(
                campaignId, briefVectorStr, hasExcluded, safeExcluded);

      // Nếu Shortlist chưa có ai, fallback sang quét toàn bộ kho Discovery (cho phép khám phá rộng)
      if (source == com.influencermatch.backend.campaign.enums.CandidateSource.DISCOVERY) {
        boolean hasPlatforms =
            campaign.getPlatforms() != null && !campaign.getPlatforms().isEmpty();
        Collection<String> safePlatforms =
            hasPlatforms ? campaign.getPlatforms() : List.of("TIKTOK");

        rawCandidates =
            publicCreatorMetricRepository.findTopCandidatesByEmbedding(
                briefVectorStr,
                hasExcluded,
                safeExcluded,
                hasPlatforms,
                safePlatforms,
                campaign.getFollowerMin(),
                campaign.getFollowerMax(),
                50);
        log.info(
            "Retrieved {} Discovery candidates via pgvector search for campaign {}",
            rawCandidates.size(),
            campaignId);
      } else {
        log.info(
            "Retrieved {} candidates from shortlist for campaign {}",
            rawCandidates.size(),
            campaignId);
      }

      if (rawCandidates.isEmpty())
        throw new BusinessException(
            ErrorCode.RECOMMENDATION_NO_CANDIDATES, "No eligible candidates in selected source");
      subscriptionId = subscriptionService.reserveRecommendationQuota(brandProfileId);
      // 5. Four-Memory Feature Scorer
      List<ScoredCandidate> scoredList = new ArrayList<>();
      for (RecommendationCandidateProjection cand : rawCandidates) {
        ScoreBreakdownDto breakdown =
            calculateFourMemoryScores(
                cand, campaign, brandProfileId, learnedSnapshot, brandContext.getProfileContext());
        scoredList.add(new ScoredCandidate(cand, breakdown));
      }

      // 6. Master-aware Grouping (Khử trùng lặp đa kênh - Mỗi KOC chỉ lấy 1 kênh điểm cao nhất)
      Map<String, ScoredCandidate> uniqueKocs = new LinkedHashMap<>();
      for (ScoredCandidate item : scoredList) {
        String masterKey =
            item.candidate.getMasterId() != null
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

      // 7. Sắp xếp điểm FinalScore giảm dần và chọn Top N (hoặc toàn bộ Shortlist nếu nhỏ hơn
      // limit)
      int effectiveLimit = limit > 0 ? Math.min(limit, 50) : 30;
      List<ScoredCandidate> topList =
          uniqueKocs.values().stream()
              .sorted(
                  Comparator.comparingDouble((ScoredCandidate sc) -> sc.breakdown.getFinalScore())
                      .reversed())
              .limit(effectiveLimit)
              .toList();

      // 8. Tích hợp XAI Engine (POST /api/v1/ai/explain kèm Circuit Breaker)
      List<CreatorRecommendationItemDto> recommendationItems = new ArrayList<>();
      for (ScoredCandidate item : topList) {
        ReasoningCardDto reasoning = fetchReasoningCard(item.candidate, campaign, item.breakdown);

        Optional<ShortlistItem> shortlistItemOpt =
            shortlistItemRepository.findByCampaignIdAndCreatorId(
                campaignId, item.candidate.getCreatorId());
        boolean inShortlist =
            shortlistItemOpt.isPresent()
                && shortlistItemOpt.get().getStatus() != ShortlistStatus.REMOVED;
        ShortlistStatus itemStatus = shortlistItemOpt.map(ShortlistItem::getStatus).orElse(null);

        recommendationItems.add(
            CreatorRecommendationItemDto.builder()
                .creatorId(item.candidate.getCreatorId())
                .masterId(item.candidate.getMasterId())
                .platform(item.candidate.getPlatform())
                .displayName(
                    item.candidate.getDisplayName() != null
                        ? item.candidate.getDisplayName()
                        : item.candidate.getUserName())
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
                .build());
      }

      // 9. Lưu vết RecommendationRun
      RecommendationRun run =
          recommendationRunRepository.save(
              RecommendationRun.builder()
                  .campaign(campaign)
                  .brandProfile(campaign.getBrandProfile())
                  .contextVersion(contextVersion)
                  .campaignContextId(campaignContext.getId())
                  .embeddingContextId(prepared.contextId())
                  .embeddingJobId(prepared.jobId())
                  .embeddingModel(prepared.model())
                  .embeddingModelVersion(prepared.modelVersion())
                  .candidateSource(source.name())
                  .brandContextId(brandContext.getId())
                  .brandContextVersion(brandContext.getContextVersion())
                  .scoringPolicyVersion("four-memory-v2-m1-unavailable")
                  .scoringSnapshot(objectMapper.valueToTree(recommendationItems))
                  .status("COMPLETED")
                  .candidatesCount(recommendationItems.size())
                  .queryBrief(queryBrief)
                  .build());

      return RecommendationResponseDto.builder()
          .recommendationRunId(run.getId())
          .campaignId(campaignId)
          .contextVersion(contextVersion)
          .totalCandidates(recommendationItems.size())
          .recommendations(recommendationItems)
          .build();

    } catch (Exception e) {
      // Rollback hoàn trả Quota nếu có bất kỳ ngoại lệ nào xảy ra trong quá trình xử lý
      if (subscriptionId != null) subscriptionService.releaseRecommendationQuota(subscriptionId);
      log.error(
          "AI recommendation failed for campaign {}, rolled back quota: {}",
          campaignId,
          e.getMessage());
      if (e instanceof BusinessException) {
        throw e;
      }
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "Failed to run AI recommendation: " + e.getMessage());
    }
  }

  /**
   * Tính toán điểm 4 tầng bộ nhớ theo Single Source of Truth của Java Backend: FinalScore = 0.50 *
   * Score_M2 + 0.30 * Score_M1 + 0.20 * Score_M4
   */
  public ScoreBreakdownDto calculateFourMemoryScores(
      RecommendationCandidateProjection cand, Campaign campaign, UUID brandProfileId) {
    return calculateFourMemoryScores(
        cand, campaign, brandProfileId, learned.snapshot(brandProfileId));
  }

  public ScoreBreakdownDto calculateFourMemoryScores(
      RecommendationCandidateProjection cand,
      Campaign campaign,
      UUID brandProfileId,
      com.influencermatch.backend.brand.service.BrandLearnedPatterns.Snapshot learnedSnapshot) {
    return calculateFourMemoryScores(
        cand, campaign, brandProfileId, learnedSnapshot, objectMapper.createObjectNode());
  }

  public ScoreBreakdownDto calculateFourMemoryScores(
      RecommendationCandidateProjection cand,
      Campaign campaign,
      UUID brandProfileId,
      com.influencermatch.backend.brand.service.BrandLearnedPatterns.Snapshot learnedSnapshot,
      JsonNode explicitContext) {

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
    if (cand.getAvgViews() != null
        && cand.getAvgViews().doubleValue() > 0
        && cand.getAvgLikes() != null) {
      effectiveEr = (cand.getAvgLikes().doubleValue() / cand.getAvgViews().doubleValue()) * 100.0;
    } else if (cand.getEngagementRate() != null) {
      effectiveEr = cand.getEngagementRate().doubleValue();
    }

    boolean isShortVideo =
        "TIKTOK".equalsIgnoreCase(cand.getPlatform())
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
    List<String> campaignNiches =
        new ArrayList<>(
            campaign.getNiches() != null ? campaign.getNiches() : Collections.emptyList());
    if (campaignNiches.isEmpty()) {
      explicitContext
          .path("productCategories")
          .forEach(
              n -> {
                if (n.isTextual() && !n.asText().isBlank()) campaignNiches.add(n.asText());
              });
      if (explicitContext.path("industry").isTextual()
          && !explicitContext.path("industry").asText().isBlank())
        campaignNiches.add(explicitContext.path("industry").asText());
    }
    String candNiche = cand.getNiche() != null ? cand.getNiche().toLowerCase() : "";
    String candCat = cand.getCategory() != null ? cand.getCategory().toLowerCase() : "";

    boolean exactMatch = campaignNiches.stream().anyMatch(n -> n.equalsIgnoreCase(candNiche));
    boolean catMatch =
        campaignNiches.stream()
            .anyMatch(n -> n.equalsIgnoreCase(candCat) || candCat.contains(n.toLowerCase()));

    if (exactMatch) {
      nicheMatchScore = 100.0;
    } else if (catMatch) {
      nicheMatchScore = 85.0;
    } else if (!candNiche.isEmpty()
        && campaignNiches.stream().anyMatch(n -> candNiche.contains(n.toLowerCase()))) {
      nicheMatchScore = 75.0;
    }

    double m2Score =
        (0.20 * followerScore)
            + (0.25 * engagementScore)
            + (0.30 * semanticMatchScore)
            + (0.25 * nicheMatchScore);

    // --- 2. Score M1 (Historical Reliability) ---
    // Evidence remains inspectable, but no M1 score exists until a scoring policy is defined.
    Double m1Score = null;
    List<HistoricalCampaign> pastCampaigns =
        historicalCampaignRepository.findByCreatorId(cand.getCreatorId());
    if (pastCampaigns.isEmpty() && cand.getMasterId() != null) {
      pastCampaigns = historicalCampaignRepository.findByMasterId(cand.getMasterId());
    }

    pastCampaigns =
        pastCampaigns.stream()
            .filter(
                h -> h.getSourceReviewId() != null && "BRAND_ACCEPTED".equals(h.getProvenance()))
            .toList();

    var cohort = learnedSnapshot.select(cand.getPlatform(), cand.getNiche());
    var evidence =
        cohort == null ? learnedSnapshot.observed(cand.getPlatform(), cand.getNiche()) : cohort;
    Double m4Score = cohort == null ? null : cohort.score();
    double total = 0.50 + (m1Score == null ? 0 : 0.30) + (m4Score == null ? 0 : 0.20);
    var effective =
        Map.of(
            "M2",
            0.50 / total,
            "M1",
            m1Score == null ? 0 : 0.30 / total,
            "M4",
            m4Score == null ? 0 : 0.20 / total);
    double finalScore =
        effective.get("M2") * m2Score
            + (m1Score == null ? 0 : effective.get("M1") * m1Score)
            + (m4Score == null ? 0 : effective.get("M4") * m4Score);

    return ScoreBreakdownDto.builder()
        .m2Score(roundToTwoDecimals(m2Score))
        .m1Score(m1Score == null ? null : roundToTwoDecimals(m1Score))
        .m4Score(m4Score == null ? null : roundToTwoDecimals(m4Score))
        .m1Available(m1Score != null)
        .m4Available(m4Score != null)
        .m1UnavailableReason(m1Score == null ? "M1_POLICY_NOT_IMPLEMENTED" : null)
        .m4UnavailableReason(m4Score == null ? "INSUFFICIENT_REVIEW_EVIDENCE" : null)
        .m1EvidenceCount(pastCampaigns.size())
        .m4EvidenceCount(evidence == null ? 0 : evidence.reviewIds().size())
        .m4Confidence(evidence == null ? null : evidence.confidence())
        .m4ReviewIds(evidence == null ? List.of() : evidence.reviewIds())
        .originalWeights(Map.of("M2", 0.50, "M1", 0.30, "M4", 0.20))
        .effectiveWeights(effective)
        .scoringPolicyVersion("four-memory-v2-m1-unavailable")
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
    Double erViews =
        (cand.getAvgViews() != null
                && cand.getAvgViews().doubleValue() > 0
                && cand.getAvgLikes() != null)
            ? (cand.getAvgLikes().doubleValue() / cand.getAvgViews().doubleValue()) * 100.0
            : null;

    ExplainRequestDto explainReq =
        ExplainRequestDto.builder()
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
            .campaignBrief(
                campaign.getProductService() != null
                    ? campaign.getProductService()
                    : campaign.getName())
            .build();

    ExplainResponseDto response = aiServiceClient.getExplanation(explainReq);

    return ReasoningCardDto.builder()
        .summary(response.getSummary())
        .reasons(response.getReasons())
        .explanationAvailable(response.isExplanationAvailable())
        .build();
  }

  /**
   * Phase 4: Feedback Loop & Tiến hóa bối cảnh M3 (Negative Constraints). Brand từ chối hoặc phản
   * hồi về KOC -> Bổ sung vào excludedCreatorIds, lưu feedbackHistory, tiến hóa phiên bản
   * CampaignContext (v_N + 1), cập nhật M4 BrandPattern. QUY TẮC AN TOÀN: Tuyệt đối không thay đổi
   * ngân sách hoặc lĩnh vực của Campaign!
   */
  @Transactional
  public RecommendationFeedbackResponse processFeedback(
      UUID campaignId, RecommendationFeedbackRequest request) {
    Permissions.require(Permission.RUN_RECOMMENDATION);
    Campaign campaign = campaignLocks.owned(campaignId);

    String action =
        request.getAction() != null ? request.getAction().trim().toUpperCase() : "REJECT";
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

    // 4. Tiến hóa phiên bản: Lưu trữ bản ghi cũ (active = false), tạo bản ghi mới (contextVersion =
    // v_N + 1)
    if (activeContext != null) {
      activeContext.setActive(false);
      activeContext.setArchivedAt(LocalDateTime.now());
      campaignContextRepository.saveAndFlush(activeContext);
    }

    int nextVersion = (activeContext != null) ? prevVersion + 1 : 1;
    CampaignContext newContext =
        CampaignContext.builder()
            .campaign(campaign)
            .contextVersion(nextVersion)
            .brandContextId(activeContext == null ? null : activeContext.getBrandContextId())
            .embeddingSourceContextId(
                activeContext == null ? null : activeContext.getEmbeddingSourceContextId())
            .contextData(dataNode)
            .active(true)
            .build();
    campaignContextRepository.save(newContext);

    // 5. Ghi nhận mô thức dài hạn M4 (BrandPattern)
    if (campaign.getBrandProfile() != null && request.getReasonCode() != null) {
      try {
        UUID brandProfileId = campaign.getBrandProfile().getId();
        BrandPattern pattern =
            brandPatternRepository
                .findByBrandProfileIdAndPatternType(brandProfileId, "REJECTION_FEEDBACK")
                .orElseGet(
                    () ->
                        BrandPattern.builder()
                            .brandProfile(campaign.getBrandProfile())
                            .patternType("REJECTION_FEEDBACK")
                            .confidence(BigDecimal.valueOf(0.5))
                            .evidenceCount(0)
                            .learnedAt(LocalDateTime.now())
                            .build());

        pattern.setEvidenceCount(pattern.getEvidenceCount() + 1);
        pattern.setConfidence(
            BigDecimal.valueOf(Math.min(1.0, 0.5 + 0.05 * pattern.getEvidenceCount())));
        pattern.setLearnedAt(LocalDateTime.now());

        ObjectNode patternValue =
            (pattern.getPatternValue() instanceof ObjectNode)
                ? (ObjectNode) pattern.getPatternValue()
                : objectMapper.createObjectNode();
        int count =
            patternValue.has(request.getReasonCode())
                ? patternValue.get(request.getReasonCode()).asInt()
                : 0;
        patternValue.put(request.getReasonCode(), count + 1);
        pattern.setPatternValue(patternValue);

        brandPatternRepository.save(pattern);
      } catch (Exception ex) {
        log.warn("Could not update M4 BrandPattern for brand: {}", ex.getMessage());
      }
    }

    log.info(
        "Feedback processed for campaign {}: creator {}, action {}, new context version {}",
        campaignId,
        creatorId,
        action,
        nextVersion);

    return RecommendationFeedbackResponse.builder()
        .campaignId(campaignId)
        .creatorId(creatorId)
        .action(action)
        .reasonCode(request.getReasonCode())
        .previousContextVersion(prevVersion)
        .newContextVersion(nextVersion)
        .totalExcludedCount(excludedArray.size())
        .message(
            action.equals("REJECT")
                ? "Đã loại trừ ứng viên khỏi các lượt gợi ý sau. Bối cảnh M3 tiến hóa lên v"
                    + nextVersion
                : "Đã ghi nhận phản hồi. Bối cảnh M3 cập nhật lên v" + nextVersion)
        .build();
  }

  public static String withBrandPreferences(String brief, Campaign c, JsonNode brand) {
    StringBuilder text = new StringBuilder(brief);
    List<String> fields = new ArrayList<>();
    if (c.getNiches() == null || c.getNiches().isEmpty()) {
      fields.add("industry");
      fields.add("productCategories");
    }
    if (c.getTargetAudiences() == null || c.getTargetAudiences().isEmpty())
      fields.add("targetAudiences");
    if (c.getPlatforms() == null || c.getPlatforms().isEmpty()) fields.add("preferredPlatforms");
    if (c.getLocations() == null || c.getLocations().isEmpty()) fields.add("targetMarkets");
    if (c.getProductService() == null || c.getProductService().isBlank()) {
      fields.add("description");
      fields.add("positioning");
    }
    fields.add("brandTone");
    fields.add("guidelines");
    for (String field : fields) {
      var value = brand.path(field);
      if (!value.isNull() && !value.isMissingNode() && (!value.isArray() || !value.isEmpty()))
        text.append("\nBrand preference ")
            .append(field)
            .append(": ")
            .append(value.isTextual() ? value.asText() : value.toString());
    }
    return text.toString();
  }

  private double roundToTwoDecimals(double val) {
    return Math.round(val * 100.0) / 100.0;
  }

  private record ScoredCandidate(
      RecommendationCandidateProjection candidate, ScoreBreakdownDto breakdown) {}
}
