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
import com.influencermatch.backend.recommendation.engine.FourMemoryTopsisEngine;
import com.influencermatch.backend.recommendation.engine.confidence.model.ConfidenceAssessment;
import com.influencermatch.backend.recommendation.engine.phase0.Phase0Orchestrator;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;
import com.influencermatch.backend.recommendation.engine.presentation.DeterministicReasoningGenerator;
import com.influencermatch.backend.recommendation.engine.presentation.DisplaySorter;
import com.influencermatch.backend.recommendation.engine.presentation.MmrReranker;
import com.influencermatch.backend.recommendation.engine.presentation.enums.DisplayMode;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
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

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.influencermatch.backend.creator.repository.PostRepository postRepository;

  public void setPostRepository(com.influencermatch.backend.creator.repository.PostRepository postRepository) {
    this.postRepository = postRepository;
  }

  private final Phase0Orchestrator phase0Orchestrator = new Phase0Orchestrator();
  private final FourMemoryTopsisEngine fourMemoryTopsisEngine = new FourMemoryTopsisEngine();
  private final DisplaySorter displaySorter = new DisplaySorter();
  private final MmrReranker mmrReranker = new MmrReranker();
  private final DeterministicReasoningGenerator deterministicReasoningGenerator = new DeterministicReasoningGenerator();

  private static final UUID DUMMY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  /**
   * Flow 3 Core: Thực thi AI Recommendation theo Four-Memory Framework (default DisplayMode.BEST_MATCH).
   */
  @Transactional
  public RecommendationResponseDto recommendKocs(UUID campaignId, int limit) {
    return recommendKocs(campaignId, limit, DisplayMode.BEST_MATCH);
  }

  /**
   * Flow 3 Core: Thực thi AI Recommendation theo Four-Memory Framework với DisplayMode chỉ định.
   * Gồm: Two-Phase Quota Reservation -> Nạp 4 tầng bộ nhớ -> pgvector Search ->
   * Four-Memory Feature Scorer -> Master-aware Grouping -> Post-Processing & Presentation -> XAI Reasoning Card.
   */
  @Transactional
  public RecommendationResponseDto recommendKocs(UUID campaignId, int limit, DisplayMode displayMode) {
    DisplayMode mode = (displayMode != null) ? displayMode : DisplayMode.BEST_MATCH;
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

        int retrievalLimit = Math.max(limit * 3, 50);
        // Theo kiến trúc InfluencerMatch: Follower Range là Soft Preference mặc định (không được lọc cứng ở SQL làm mất ứng viên tốt).
        // Chỉ Platform và Negative Constraints (Brand Exclusion) là Hard Constraints được lọc trước ở SQL.
        rawCandidates =
            publicCreatorMetricRepository.findTopCandidatesByEmbedding(
                briefVectorStr,
                hasExcluded,
                safeExcluded,
                hasPlatforms,
                safePlatforms,
                null, // followerMin = null (Soft Preference)
                null, // followerMax = null (Soft Preference)
                retrievalLimit
            );
        log.info("Shortlist was empty. Retrieved {} raw candidates via pgvector search for campaign {}", rawCandidates.size(), campaignId);
      } else {
        log.info("Retrieved {} candidates from shortlist for campaign {}", rawCandidates.size(), campaignId);
      }

      // 5. Phase 0: Eligibility Checking, Evidence Validation & Policy-Based Normalization
      Map<UUID, List<com.influencermatch.backend.creator.model.Post>> postsByCreator = Collections.emptyMap();
      if (postRepository != null && !rawCandidates.isEmpty()) {
        List<UUID> candidateIds = rawCandidates.stream()
            .map(RecommendationCandidateProjection::getCreatorId)
            .filter(Objects::nonNull)
            .toList();
        List<com.influencermatch.backend.creator.model.Post> posts = postRepository.findByCreatorIdIn(candidateIds);
        if (posts != null && !posts.isEmpty()) {
          // Gom nhóm toàn bộ bài đăng theo creatorId để EvidenceValidator đánh giá đúng Historical Count
          // và tự động chọn chính xác cửa sổ 3 bài gần nhất qua selectRecentWindow()
          postsByCreator = posts.stream()
              .filter(p -> p != null && p.getCreator() != null && p.getCreator().getId() != null)
              .collect(Collectors.groupingBy(p -> p.getCreator().getId()));
        }
      }

      List<NormalizedCandidateContext> phase0Results =
          phase0Orchestrator.execute(rawCandidates, campaign, excludedIds, postsByCreator);

      Map<UUID, NormalizedCandidateContext> phase0Map = phase0Results.stream()
          .collect(Collectors.toMap(NormalizedCandidateContext::creatorId, r -> r, (a, b) -> a));

      // Filter candidates: Tách biệt hoàn toàn Main Ranking Pool (chỉ SCORE_READY) và Provisional Pool
      List<RecommendationCandidateProjection> scoreReadyCandidates = new ArrayList<>();
      List<RecommendationCandidateProjection> provisionalCandidates = new ArrayList<>();

      for (RecommendationCandidateProjection cand : rawCandidates) {
        NormalizedCandidateContext p0 = phase0Map.get(cand.getCreatorId());
        if (p0 == null) continue;

        if (!p0.isCampaignEligible()) {
          log.info("Candidate {} excluded: Ineligible for campaign: {}", cand.getCreatorId(), p0.eligibilityReasons());
          continue;
        }

        // Phân luồng tường minh theo ScoringReadiness - tuyệt đối không dùng else mặc định để tránh NOT_SCORABLE lọt vào Provisional Pool
        switch (p0.scoringReadiness()) {
          case SCORE_READY -> {
            scoreReadyCandidates.add(cand);
          }
          case PROVISIONAL -> {
            log.info("Candidate {} routed to provisional pool (lacks full stability evidence): {}", cand.getCreatorId(), p0.dataQualityReasons());
            provisionalCandidates.add(cand);
          }
          case NOT_SCORABLE -> {
            log.info("Candidate {} excluded: Not scorable under current SM2 policy: {}", cand.getCreatorId(), p0.dataQualityReasons());
          }
          case INVALID_DATA -> {
            log.warn("Candidate {} excluded: Data quality error (not a campaign violation): {}", cand.getCreatorId(), p0.dataQualityReasons());
          }
        }
      }

      // 6. Phase 1-5: BẢNG XẾP HẠNG CHÍNH - Chỉ SCORE_READY được tham gia CRITIC Reference Pool & TOPSIS chính!
      List<BrandPattern> brandPatterns = brandPatternRepository.findByBrandProfileId(brandProfileId);
      Function<RecommendationCandidateProjection, List<HistoricalCampaign>> historyProvider = cand -> {
        List<HistoricalCampaign> past = historicalCampaignRepository.findByCreatorId(cand.getCreatorId());
        if (past.isEmpty() && cand.getMasterId() != null) {
          past = historicalCampaignRepository.findByMasterId(cand.getMasterId());
        }
        return past;
      };

      List<FourMemoryTopsisEngine.EvaluatedCandidate> evaluatedReadyList = Collections.emptyList();
      if (!scoreReadyCandidates.isEmpty()) {
        evaluatedReadyList = fourMemoryTopsisEngine.evaluateAndRank(
            scoreReadyCandidates,
            phase0Map,
            campaign,
            historyProvider,
            brandPatterns
        );
      }

      // Lấy weight vector của run: nếu bảng chính có ứng viên thì dùng weights của run, ngược lại dùng fallback
      com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights runWeights = !evaluatedReadyList.isEmpty()
          ? evaluatedReadyList.get(0).weights()
          : fourMemoryTopsisEngine.getFallbackWeights(campaign != null && campaign.getObjective() != null ? campaign.getObjective().name() : "AWARENESS");

      // Đánh giá sơ bộ (Provisional Reference Score) độc lập với cùng bộ trọng số runWeights
      List<FourMemoryTopsisEngine.EvaluatedCandidate> evaluatedProvisionalList = new ArrayList<>();
      for (RecommendationCandidateProjection cand : provisionalCandidates) {
        NormalizedCandidateContext p0 = phase0Map.get(cand.getCreatorId());
        List<HistoricalCampaign> past = historyProvider.apply(cand);
        evaluatedProvisionalList.add(fourMemoryTopsisEngine.evaluateSingle(cand, p0, campaign, past, brandPatterns, runWeights));
      }

      // 7. Master-aware Grouping trên Main Ranking Pool (SCORE_READY)
      // Master Dedup diễn ra trước limit, dùng chung Comparator với Display Sorting
      Comparator<DisplaySorter.DisplayableItem> dedupComparator = DisplaySorter.resolveComparator(mode);
      Map<String, FourMemoryTopsisEngine.EvaluatedCandidate> uniqueKocs = new LinkedHashMap<>();
      for (FourMemoryTopsisEngine.EvaluatedCandidate item : evaluatedReadyList) {
        String masterKey = item.candidate().getMasterId() != null
            ? item.candidate().getMasterId().toString()
            : item.candidate().getCreatorId().toString();

        if (!uniqueKocs.containsKey(masterKey)) {
          uniqueKocs.put(masterKey, item);
        } else {
          FourMemoryTopsisEngine.EvaluatedCandidate existing = uniqueKocs.get(masterKey);
          if (dedupComparator.compare(item, existing) < 0) {
            uniqueKocs.put(masterKey, item);
          }
        }
      }

      // 8. Sắp xếp hiển thị và đa dạng hóa (Post-Processing & Presentation)
      int effectiveLimit = limit > 0 ? Math.min(limit, 50) : 30;
      List<FourMemoryTopsisEngine.EvaluatedCandidate> pool = new ArrayList<>(uniqueKocs.values());
      List<FourMemoryTopsisEngine.EvaluatedCandidate> topList;

      switch (mode) {
        case DIVERSIFIED_MATCH -> {
          // 1. Initial relevance ordering strictly by TOPSIS MatchScore
          List<FourMemoryTopsisEngine.EvaluatedCandidate> relevanceSorted =
              displaySorter.sort(pool, DisplayMode.BEST_MATCH);
          // 2. MMR diversity reranking with lambda = 0.70
          topList = mmrReranker.rerank(relevanceSorted, effectiveLimit, MmrReranker.DEFAULT_LAMBDA);
        }
        case BUDGET_READY_FIRST -> {
          // Bucketed by PriceStatus priority, then MatchScore descending
          List<FourMemoryTopsisEngine.EvaluatedCandidate> budgetSorted =
              displaySorter.sort(pool, DisplayMode.BUDGET_READY_FIRST);
          topList = budgetSorted.stream().limit(effectiveLimit).toList();
        }
        case BEST_MATCH -> {
          // Pure relevance: strictly ordered by TOPSIS MatchScore descending without diversity perturbation
          List<FourMemoryTopsisEngine.EvaluatedCandidate> matchSorted =
              displaySorter.sort(pool, DisplayMode.BEST_MATCH);
          topList = matchSorted.stream().limit(effectiveLimit).toList();
        }
        default -> {
          List<FourMemoryTopsisEngine.EvaluatedCandidate> matchSorted =
              displaySorter.sort(pool, DisplayMode.BEST_MATCH);
          topList = matchSorted.stream().limit(effectiveLimit).toList();
        }
      }

      // 9. Tích hợp XAI Engine (POST /api/v1/ai/explain kèm Circuit Breaker & Fallback) cho Bảng chính
      // Hợp đồng DTO: recommendations CHỈ chứa ứng viên SCORE_READY (hoàn tất Four-Memory)
      List<CreatorRecommendationItemDto> recommendationItems = new ArrayList<>();
      for (FourMemoryTopsisEngine.EvaluatedCandidate item : topList) {
        ScoreBreakdownDto breakdown = fourMemoryTopsisEngine.toScoreBreakdownDto(item);
        ReasoningCardDto reasoning = fetchReasoningCard(item.candidate(), campaign, breakdown, item.confidence());

        Optional<ShortlistItem> shortlistItemOpt =
            shortlistItemRepository.findByCampaignIdAndCreatorId(campaignId, item.candidate().getCreatorId());
        boolean inShortlist = shortlistItemOpt.isPresent() && shortlistItemOpt.get().getStatus() != ShortlistStatus.REMOVED;
        ShortlistStatus itemStatus = shortlistItemOpt.map(ShortlistItem::getStatus).orElse(null);

        recommendationItems.add(
            CreatorRecommendationItemDto.builder()
                .creatorId(item.candidate().getCreatorId())
                .masterId(item.candidate().getMasterId())
                .platform(item.candidate().getPlatform())
                .displayName(item.candidate().getDisplayName() != null ? item.candidate().getDisplayName() : item.candidate().getUserName())
                .userName(item.candidate().getUserName())
                .avatarUrl(item.candidate().getAvatarUrl())
                .followers(item.candidate().getFollowers())
                .avgViews(item.candidate().getAvgViews())
                .avgLikes(item.candidate().getAvgLikes())
                .engagementRate(item.candidate().getEngagementRate())
                .niche(item.candidate().getNiche())
                .category(item.candidate().getCategory())
                .creatorType(item.candidate().getCreatorType())
                .isClaimed(item.candidate().getMasterId() != null)
                .isInShortlist(inShortlist)
                .shortlistStatus(itemStatus)
                .finalScore(breakdown.getFinalScore())
                .scoreBreakdown(breakdown)
                .reasoning(reasoning)
                .isScoreReady(true)
                .isProvisional(false)
                .build()
        );
      }

      // 10. Master-aware Grouping trên Provisional Pool & Xây dựng danh sách Provisional tách bạch hoàn toàn
      // Hợp đồng DTO: provisionalRecommendations CHỈ chứa ứng viên PROVISIONAL (đánh giá sơ bộ thiếu STAB)
      // Bảo đảm tính không giao nhau:
      // 1. Về creatorId: recommendations ∩ provisionalRecommendations = ∅
      // 2. Về masterId: Creator Master đã có kênh đại diện trong Bảng chính (SCORE_READY) thì không xuất hiện lại trong Provisional
      Set<String> recommendedMasterKeys = topList.stream()
          .map(item -> item.candidate().getMasterId() != null
              ? item.candidate().getMasterId().toString()
              : item.candidate().getCreatorId().toString())
          .collect(Collectors.toSet());

      Map<String, FourMemoryTopsisEngine.EvaluatedCandidate> uniqueProvisionalKocs = new LinkedHashMap<>();
      for (FourMemoryTopsisEngine.EvaluatedCandidate item : evaluatedProvisionalList) {
        String masterKey = item.candidate().getMasterId() != null
            ? item.candidate().getMasterId().toString()
            : item.candidate().getCreatorId().toString();

        if (recommendedMasterKeys.contains(masterKey)) {
          continue; // Creator Master này đã có kênh đại diện trong Bảng chính
        }

        if (!uniqueProvisionalKocs.containsKey(masterKey)) {
          uniqueProvisionalKocs.put(masterKey, item);
        } else {
          FourMemoryTopsisEngine.EvaluatedCandidate existing = uniqueProvisionalKocs.get(masterKey);
          if (dedupComparator.compare(item, existing) < 0) {
            uniqueProvisionalKocs.put(masterKey, item);
          }
        }
      }

      List<FourMemoryTopsisEngine.EvaluatedCandidate> sortedProvisional =
          displaySorter.sort(new ArrayList<>(uniqueProvisionalKocs.values()), mode);
      List<FourMemoryTopsisEngine.EvaluatedCandidate> topProvisional = sortedProvisional.stream()
          .limit(effectiveLimit)
          .toList();

      List<CreatorRecommendationItemDto> provisionalItems = new ArrayList<>();
      for (FourMemoryTopsisEngine.EvaluatedCandidate item : topProvisional) {
        ScoreBreakdownDto breakdown = fourMemoryTopsisEngine.toScoreBreakdownDto(item);
        ReasoningCardDto reasoning = fetchReasoningCard(item.candidate(), campaign, breakdown, item.confidence());

        Optional<ShortlistItem> shortlistItemOpt =
            shortlistItemRepository.findByCampaignIdAndCreatorId(campaignId, item.candidate().getCreatorId());
        boolean inShortlist = shortlistItemOpt.isPresent() && shortlistItemOpt.get().getStatus() != ShortlistStatus.REMOVED;
        ShortlistStatus itemStatus = shortlistItemOpt.map(ShortlistItem::getStatus).orElse(null);

        provisionalItems.add(
            CreatorRecommendationItemDto.builder()
                .creatorId(item.candidate().getCreatorId())
                .masterId(item.candidate().getMasterId())
                .platform(item.candidate().getPlatform())
                .displayName(item.candidate().getDisplayName() != null ? item.candidate().getDisplayName() : item.candidate().getUserName())
                .userName(item.candidate().getUserName())
                .avatarUrl(item.candidate().getAvatarUrl())
                .followers(item.candidate().getFollowers())
                .avgViews(item.candidate().getAvgViews())
                .avgLikes(item.candidate().getAvgLikes())
                .engagementRate(item.candidate().getEngagementRate())
                .niche(item.candidate().getNiche())
                .category(item.candidate().getCategory())
                .creatorType(item.candidate().getCreatorType())
                .isClaimed(item.candidate().getMasterId() != null)
                .isInShortlist(inShortlist)
                .shortlistStatus(itemStatus)
                .finalScore(breakdown.getFinalScore())
                .scoreBreakdown(breakdown)
                .reasoning(reasoning)
                .isScoreReady(false)
                .isProvisional(true)
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
          .provisionalRecommendations(provisionalItems)
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
    return calculateFourMemoryScores(cand, campaign, brandProfileId, null);
  }

  public ScoreBreakdownDto calculateFourMemoryScores(
      RecommendationCandidateProjection cand,
      Campaign campaign,
      UUID brandProfileId,
      NormalizedCandidateContext p0) {

    List<HistoricalCampaign> pastCampaigns = historicalCampaignRepository.findByCreatorId(cand.getCreatorId());
    if (pastCampaigns.isEmpty() && cand.getMasterId() != null) {
      pastCampaigns = historicalCampaignRepository.findByMasterId(cand.getMasterId());
    }

    List<BrandPattern> patterns = (brandProfileId != null)
        ? brandPatternRepository.findByBrandProfileId(brandProfileId)
        : Collections.emptyList();

    FourMemoryTopsisEngine.EvaluatedCandidate evaluated =
        fourMemoryTopsisEngine.evaluateSingle(cand, p0, campaign, pastCampaigns, patterns);

    return fourMemoryTopsisEngine.toScoreBreakdownDto(evaluated);
  }

  private ReasoningCardDto fetchReasoningCard(
      RecommendationCandidateProjection cand, Campaign campaign, ScoreBreakdownDto breakdown) {
    return fetchReasoningCard(cand, campaign, breakdown, null);
  }

  private ReasoningCardDto fetchReasoningCard(
      RecommendationCandidateProjection cand,
      Campaign campaign,
      ScoreBreakdownDto breakdown,
      ConfidenceAssessment confidence) {

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

    try {
      ExplainResponseDto response = aiServiceClient.getExplanation(explainReq);
      if (response != null && response.isExplanationAvailable()
          && response.getSummary() != null && !response.getSummary().isBlank()) {
        List<String> combinedReasons = new ArrayList<>();
        if (response.getReasons() != null) {
          combinedReasons.addAll(response.getReasons());
        }
        List<String> warnings = (confidence != null && confidence.evidenceWarnings() != null)
            ? confidence.evidenceWarnings()
            : Collections.emptyList();
        for (String warning : warnings) {
          combinedReasons.add("Lưu ý: " + warning);
        }
        return ReasoningCardDto.builder()
            .summary(response.getSummary())
            .reasons(combinedReasons)
            .explanationAvailable(true)
            .evidenceWarnings(warnings)
            .build();
      }
    } catch (Exception e) {
      log.warn("AI service explanation call failed for creator {}, falling back to deterministic template: {}",
          cand.getCreatorId(), e.getMessage());
    }

    return deterministicReasoningGenerator.generate(cand, campaign, breakdown, confidence);
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
