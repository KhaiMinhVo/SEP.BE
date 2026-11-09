package com.influencermatch.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.billing.service.SubscriptionService;
import com.influencermatch.backend.brand.model.BrandProfile;
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
import com.influencermatch.backend.recommendation.dto.ExplainResponseDto;
import com.influencermatch.backend.recommendation.dto.RecommendationResponseDto;
import com.influencermatch.backend.recommendation.dto.ScoreBreakdownDto;
import com.influencermatch.backend.recommendation.service.AiRecommendationService;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class AiRecommendationServicePhase2Test {

  private SubscriptionService subscriptionService;
  private CampaignOwnershipService ownership;
  private CampaignContextRepository campaignContextRepository;
  private PublicCreatorMetricRepository publicCreatorMetricRepository;
  private HistoricalCampaignRepository historicalCampaignRepository;
  private BrandPatternRepository brandPatternRepository;
  private ShortlistItemRepository shortlistItemRepository;
  private RecommendationRunRepository recommendationRunRepository;
  private AiServiceClient aiServiceClient;

  private AiRecommendationService service;

  private User brandUser;
  private BrandProfile brandProfile;
  private Campaign campaign;
  private UUID campaignId;
  private UUID brandProfileId;
  private UUID subscriptionId;

  @BeforeEach
  void setUp() {
    subscriptionService = mock(SubscriptionService.class);
    ownership = mock(CampaignOwnershipService.class);
    campaignContextRepository = mock(CampaignContextRepository.class);
    publicCreatorMetricRepository = mock(PublicCreatorMetricRepository.class);
    historicalCampaignRepository = mock(HistoricalCampaignRepository.class);
    brandPatternRepository = mock(BrandPatternRepository.class);
    shortlistItemRepository = mock(ShortlistItemRepository.class);
    recommendationRunRepository = mock(RecommendationRunRepository.class);
    aiServiceClient = mock(AiServiceClient.class);

    service = new AiRecommendationService(
        subscriptionService,
        ownership,
        campaignContextRepository,
        publicCreatorMetricRepository,
        historicalCampaignRepository,
        brandPatternRepository,
        shortlistItemRepository,
        recommendationRunRepository,
        aiServiceClient,
        new com.fasterxml.jackson.databind.ObjectMapper()
    );

    com.influencermatch.backend.creator.repository.PostRepository defaultPostRepo =
        mock(com.influencermatch.backend.creator.repository.PostRepository.class);
    when(defaultPostRepo.findByCreatorIdIn(any())).thenAnswer(inv -> {
      Collection<UUID> ids = inv.getArgument(0);
      if (ids == null) return Collections.emptyList();
      List<com.influencermatch.backend.creator.model.Post> list = new ArrayList<>();
      for (UUID id : ids) {
        com.influencermatch.backend.creator.model.Creator c =
            com.influencermatch.backend.creator.model.Creator.builder().id(id).build();
        list.add(com.influencermatch.backend.creator.model.Post.builder().id(UUID.randomUUID()).creator(c).views(10_000L).likes(500L).comments(50L).shares(10L).postedAt("2026-03-01T10:00:00Z").build());
        list.add(com.influencermatch.backend.creator.model.Post.builder().id(UUID.randomUUID()).creator(c).views(12_000L).likes(600L).comments(60L).shares(12L).postedAt("2026-03-02T10:00:00Z").build());
        list.add(com.influencermatch.backend.creator.model.Post.builder().id(UUID.randomUUID()).creator(c).views(15_000L).likes(700L).comments(70L).shares(15L).postedAt("2026-03-03T10:00:00Z").build());
      }
      return list;
    });
    service.setPostRepository(defaultPostRepo);

    brandUser = TestActors.user(Role.BRAND);
    TestActors.authenticate(brandUser);

    campaignId = UUID.randomUUID();
    brandProfileId = UUID.randomUUID();
    subscriptionId = UUID.randomUUID();

    brandProfile = BrandProfile.builder()
        .id(brandProfileId)
        .user(brandUser)
        .build();

    campaign = Campaign.builder()
        .id(campaignId)
        .brandProfile(brandProfile)
        .name("Summer Beauty Campaign")
        .productService("Son dưỡng ẩm hữu cơ")
        .niches(List.of("Beauty", "Skincare"))
        .platforms(List.of("TIKTOK", "INSTAGRAM"))
        .followerMin(10_000L)
        .followerMax(500_000L)
        .build();

    when(ownership.requireOwned(campaignId)).thenReturn(campaign);
    when(subscriptionService.reserveRecommendationQuota(brandProfileId)).thenReturn(subscriptionId);
    when(aiServiceClient.getEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
    when(recommendationRunRepository.save(any(RecommendationRun.class))).thenAnswer(inv -> {
      RecommendationRun r = inv.getArgument(0);
      r.setId(UUID.randomUUID());
      return r;
    });

    when(aiServiceClient.getExplanation(any())).thenReturn(
        ExplainResponseDto.builder()
            .status("success")
            .summary("KOC có độ tương thích cao với dòng sản phẩm làm đẹp.")
            .reasons(List.of("ER tốt", "Niche khớp chính xác"))
            .explanationAvailable(true)
            .build()
    );
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("TEST-04: Master-aware Grouping - KOC có nhiều kênh (TikTok & IG) chỉ hiển thị 1 kênh điểm cao nhất")
  void testMasterAwareGrouping_picksBestChannel() {
    UUID masterId = UUID.randomUUID();
    UUID tiktokId = UUID.randomUUID();
    UUID instagramId = UUID.randomUUID();

    // Kênh 1: TikTok với ER thấp
    RecommendationCandidateProjection tiktokCand = mockCandidate(
        tiktokId, masterId, "TIKTOK", "Trang Dao TikTok", 50_000L,
        BigDecimal.valueOf(10_000), BigDecimal.valueOf(200), BigDecimal.valueOf(0.02),
        "Beauty", "Cosmetics", 0.70
    );

    // Kênh 2: Instagram cùng KOC nhưng ER và semantic cao hơn
    RecommendationCandidateProjection igCand = mockCandidate(
        instagramId, masterId, "INSTAGRAM", "Trang Dao IG", 80_000L,
        BigDecimal.valueOf(20_000), BigDecimal.valueOf(1_500), BigDecimal.valueOf(0.075),
        "Beauty", "Skincare", 0.82
    );

    // KOC khác độc lập
    UUID otherId = UUID.randomUUID();
    RecommendationCandidateProjection otherCand = mockCandidate(
        otherId, null, "TIKTOK", "Lan Anh Review", 30_000L,
        BigDecimal.valueOf(15_000), BigDecimal.valueOf(900), BigDecimal.valueOf(0.06),
        "Beauty", "Lifestyle", 0.75
    );

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
        anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(tiktokCand, igCand, otherCand));

    RecommendationResponseDto response = service.recommendKocs(campaignId, 10);

    assertThat(response.getRecommendations()).hasSize(2);
    // KOC masterId xuất hiện đúng 1 lần, chọn kênh Instagram điểm cao hơn
    var masterItem = response.getRecommendations().stream()
        .filter(r -> masterId.equals(r.getMasterId()))
        .findFirst()
        .orElseThrow();

    assertThat(masterItem.getPlatform()).isEqualTo("INSTAGRAM");
    assertThat(masterItem.getCreatorId()).isEqualTo(instagramId);
  }

  @Test
  @DisplayName("TEST-05: Neutral M1 Score - KOC chưa từng hợp tác nhận điểm trung tính 50.0")
  void testNeutralM1Score_forNewCreator() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(
        creatorId, null, "TIKTOK", "Newbie KOC", 25_000L,
        BigDecimal.valueOf(10_000), BigDecimal.valueOf(500), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.60
    );

    when(historicalCampaignRepository.findByCreatorId(creatorId)).thenReturn(Collections.emptyList());

    ScoreBreakdownDto score = service.calculateFourMemoryScores(cand, campaign, brandProfileId);

    assertThat(score.getM1Score()).isEqualTo(50.0);
  }

  @Test
  @DisplayName("Four-Memory Scoring & TOPSIS: Đảm bảo đầy đủ 4 điểm SM1..SM4 và Match Score TOPSIS hợp lệ")
  void testFourMemoryScores_andTopsisCalculation() {
    UUID normalId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(
        normalId, null, "TIKTOK", "Fit KOC", 50_000L,
        BigDecimal.valueOf(25_000), BigDecimal.valueOf(1_250), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.80
    );
    ScoreBreakdownDto score = service.calculateFourMemoryScores(cand, campaign, brandProfileId);
    assertThat(score.getM1Score()).isNotNull().isBetween(0.0, 100.0);
    assertThat(score.getM2Score()).isNotNull().isBetween(0.0, 100.0);
    assertThat(score.getM3Score()).isNotNull().isBetween(0.0, 100.0);
    assertThat(score.getM4Score()).isNotNull().isBetween(0.0, 100.0);
    assertThat(score.getFinalScore()).isNotNull().isBetween(0.0, 100.0);
  }

  @Test
  @DisplayName("Circuit Breaker Fallback: Khi Python AI explain lỗi, trả về thẻ mặc định với explanationAvailable = false")
  void testCircuitBreaker_fallbackWhenAiUnavailable() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(
        creatorId, null, "TIKTOK", "Mai Anh", 20_000L,
        BigDecimal.valueOf(10_000), BigDecimal.valueOf(500), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.65
    );

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
        anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(cand));

    // Giả lập Python service timeout / crash trả về fallback
    when(aiServiceClient.getExplanation(any())).thenReturn(
        ExplainResponseDto.builder()
            .status("fallback")
            .summary("Ứng viên được đề xuất dựa trên mô hình Four-Memory.")
            .reasons(List.of("Chỉ số phù hợp"))
            .explanationAvailable(false)
            .build()
    );

    RecommendationResponseDto response = service.recommendKocs(campaignId, 10);

    assertThat(response.getRecommendations()).hasSize(1);
    var item = response.getRecommendations().get(0);
    assertThat(item.getReasoning().isExplanationAvailable()).isFalse();
    assertThat(item.getReasoning().getSummary()).contains("Four-Memory");
  }

  @Test
  @DisplayName("TEST-02: Rollback Quota khi xử lý downstream gặp lỗi")
  void testRollbackQuota_onDownstreamError() {
    when(aiServiceClient.getEmbedding(anyString())).thenThrow(new RuntimeException("Network crash"));

    assertThatThrownBy(() -> service.recommendKocs(campaignId, 10))
        .isInstanceOf(RuntimeException.class);

    // Xác nhận quota reservation được rollback hoàn trả
    verify(subscriptionService).releaseRecommendationQuota(subscriptionId);
  }

  @Test
  @DisplayName("Phase 0 & TOPSIS: Provisional Creator có ER/VIEW cao không tham gia TOPSIS chính nếu chưa SCORE_READY")
  void testProvisionalCreatorWithHighEr_doesNotPolluteMainTopsis() {
    UUID readyId = UUID.randomUUID();
    UUID provId = UUID.randomUUID();

    // Creator 1: Đủ bài (Score-ready)
    RecommendationCandidateProjection readyCand = mockCandidate(
        readyId, null, "TIKTOK", "Ready KOC", 50_000L,
        BigDecimal.valueOf(20_000), BigDecimal.valueOf(1_000), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.70
    );

    // Creator 2: Thiếu bài (Provisional) nhưng có ER và VIEW rất cao (0.20 và 40k views)
    RecommendationCandidateProjection provCand = mockCandidate(
        provId, null, "TIKTOK", "Viral Provisional KOC", 50_000L,
        BigDecimal.valueOf(40_000), BigDecimal.valueOf(8_000), BigDecimal.valueOf(0.20),
        "Beauty", "Skincare", 0.90
    );

    // Mock postRepository: chỉ readyId có 3 bài posts
    com.influencermatch.backend.creator.repository.PostRepository mockPostRepo =
        mock(com.influencermatch.backend.creator.repository.PostRepository.class);
    com.influencermatch.backend.creator.model.Creator creatorEntity =
        com.influencermatch.backend.creator.model.Creator.builder().id(readyId).build();
    com.influencermatch.backend.creator.model.Post p1 =
        com.influencermatch.backend.creator.model.Post.builder().creator(creatorEntity).views(18_000L).likes(900L).build();
    com.influencermatch.backend.creator.model.Post p2 =
        com.influencermatch.backend.creator.model.Post.builder().creator(creatorEntity).views(20_000L).likes(1_000L).build();
    com.influencermatch.backend.creator.model.Post p3 =
        com.influencermatch.backend.creator.model.Post.builder().creator(creatorEntity).views(22_000L).likes(1_100L).build();
    when(mockPostRepo.findByCreatorIdIn(any())).thenReturn(List.of(p1, p2, p3));

    service.setPostRepository(mockPostRepo);

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
        anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(readyCand, provCand));

    RecommendationResponseDto response = service.recommendKocs(campaignId, 10);

    // Hợp đồng DTO: recommendations CHỈ chứa ứng viên SCORE_READY (bảng chính TOPSIS)
    assertThat(response.getRecommendations()).hasSize(1);
    var readyItem = response.getRecommendations().get(0);
    assertThat(readyItem.getCreatorId()).isEqualTo(readyId);
    assertThat(readyItem.getIsScoreReady()).isTrue();
    assertThat(readyItem.getIsProvisional()).isFalse();

    // Bể provisionalRecommendations phân tách riêng biệt, CHỈ chứa ứng viên PROVISIONAL
    assertThat(response.getProvisionalRecommendations()).hasSize(1);
    var provItem = response.getProvisionalRecommendations().get(0);
    assertThat(provItem.getCreatorId()).isEqualTo(provId);
    assertThat(provItem.getIsScoreReady()).isFalse();
    assertThat(provItem.getIsProvisional()).isTrue();

    // Hai danh sách hoàn toàn không giao nhau
    assertThat(response.getRecommendations()).noneMatch(r -> r.getCreatorId().equals(provId));
    assertThat(response.getProvisionalRecommendations()).noneMatch(r -> r.getCreatorId().equals(readyId));
  }

  @Test
  @DisplayName("Service Integration: Run có đủ 4 trạng thái Readiness - NOT_SCORABLE và INVALID_DATA bị loại hoàn toàn, Main và Provisional hoàn toàn không giao nhau")
  void testService_fourReadinessStates_notScorableAndInvalidDataExcludedFromBothRecommendationsAndProvisional() {
    UUID readyId = UUID.randomUUID();
    UUID provId = UUID.randomUUID();
    UUID notScorableId = UUID.randomUUID();
    UUID invalidDataId = UUID.randomUUID();

    // 1. SCORE_READY
    RecommendationCandidateProjection readyCand = mockCandidate(
        readyId, null, "TIKTOK", "Ready KOC", 50_000L,
        BigDecimal.valueOf(20_000), BigDecimal.valueOf(1_000), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.75
    );

    // 2. PROVISIONAL (chỉ 1 bài -> thiếu STAB)
    RecommendationCandidateProjection provCand = mockCandidate(
        provId, null, "TIKTOK", "Provisional KOC", 50_000L,
        BigDecimal.valueOf(15_000), BigDecimal.valueOf(750), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.70
    );

    // 3. NOT_SCORABLE (3 bài video nhưng toàn bộ Views = 0 -> 0/0 NaN)
    RecommendationCandidateProjection notScorableCand = mockCandidate(
        notScorableId, null, "TIKTOK", "Zero Views Not Scorable KOC", 50_000L,
        BigDecimal.valueOf(0), BigDecimal.valueOf(0), BigDecimal.valueOf(0.0),
        "Beauty", "Skincare", 0.65
    );

    // 4. INVALID_DATA (likes âm vi phạm hợp đồng)
    RecommendationCandidateProjection invalidDataCand = mockCandidate(
        invalidDataId, null, "TIKTOK", "Corrupted Metric KOC", 50_000L,
        BigDecimal.valueOf(10_000), BigDecimal.valueOf(-500), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.60
    );

    // Mock Posts
    com.influencermatch.backend.creator.repository.PostRepository mockPostRepo =
        mock(com.influencermatch.backend.creator.repository.PostRepository.class);

    com.influencermatch.backend.creator.model.Creator readyEntity =
        com.influencermatch.backend.creator.model.Creator.builder().id(readyId).build();
    com.influencermatch.backend.creator.model.Creator provEntity =
        com.influencermatch.backend.creator.model.Creator.builder().id(provId).build();
    com.influencermatch.backend.creator.model.Creator notScorableEntity =
        com.influencermatch.backend.creator.model.Creator.builder().id(notScorableId).build();

    List<com.influencermatch.backend.creator.model.Post> allPosts = List.of(
        // ready: 3 bài views > 0
        com.influencermatch.backend.creator.model.Post.builder().creator(readyEntity).views(18_000L).likes(900L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(readyEntity).views(20_000L).likes(1_000L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(readyEntity).views(22_000L).likes(1_100L).build(),
        // prov: 1 bài duy nhất
        com.influencermatch.backend.creator.model.Post.builder().creator(provEntity).views(15_000L).likes(750L).build(),
        // notScorable: 3 bài nhưng toàn bộ views = 0
        com.influencermatch.backend.creator.model.Post.builder().creator(notScorableEntity).views(0L).likes(0L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(notScorableEntity).views(0L).likes(0L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(notScorableEntity).views(0L).likes(0L).build()
    );

    when(mockPostRepo.findByCreatorIdIn(any())).thenReturn(allPosts);
    service.setPostRepository(mockPostRepo);

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
        anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(readyCand, provCand, notScorableCand, invalidDataCand));

    // Thực thi Recommendation Run
    RecommendationResponseDto response = service.recommendKocs(campaignId, 10);

    // 1. Run hoàn thành an toàn (không bị crash bởi NaN hay exception)
    assertThat(response).isNotNull();

    // 2. Bảng xếp hạng chính (Recommendations): CHỈ chứa ứng viên SCORE_READY (readyCand)
    // Tuyệt đối KHÔNG chứa provCand, notScorableCand hay invalidDataCand
    assertThat(response.getRecommendations()).hasSize(1);
    assertThat(response.getRecommendations().get(0).getCreatorId()).isEqualTo(readyId);
    assertThat(response.getRecommendations()).noneMatch(r -> r.getCreatorId().equals(provId));
    assertThat(response.getRecommendations()).noneMatch(r -> r.getCreatorId().equals(notScorableId));
    assertThat(response.getRecommendations()).noneMatch(r -> r.getCreatorId().equals(invalidDataId));

    // 3. Bể Provisional Recommendations: CHỈ chứa provCand
    // Tuyệt đối KHÔNG chứa readyCand, notScorableCand hay invalidDataCand
    assertThat(response.getProvisionalRecommendations()).hasSize(1);
    assertThat(response.getProvisionalRecommendations().get(0).getCreatorId()).isEqualTo(provId);
    assertThat(response.getProvisionalRecommendations()).noneMatch(r -> r.getCreatorId().equals(readyId));
    assertThat(response.getProvisionalRecommendations()).noneMatch(r -> r.getCreatorId().equals(notScorableId));
    assertThat(response.getProvisionalRecommendations()).noneMatch(r -> r.getCreatorId().equals(invalidDataId));

    // 4. Hai danh sách có tập Creator ID hoàn toàn không giao nhau (Strictly Disjoint)
    java.util.Set<UUID> mainIds = response.getRecommendations().stream()
        .map(com.influencermatch.backend.recommendation.dto.CreatorRecommendationItemDto::getCreatorId)
        .collect(java.util.stream.Collectors.toSet());
    java.util.Set<UUID> provIds = response.getProvisionalRecommendations().stream()
        .map(com.influencermatch.backend.recommendation.dto.CreatorRecommendationItemDto::getCreatorId)
        .collect(java.util.stream.Collectors.toSet());
    assertThat(mainIds).doesNotContainAnyElementsOf(provIds);
  }

  @Test
  @DisplayName("Service Integration: Khi không có ứng viên SCORE_READY, chỉ có PROVISIONAL - recommendations rỗng, provisionalRecommendations có dữ liệu")
  void testService_zeroScoreReadyCandidates_returnsEmptyMainAndPopulatedProvisional() {
    UUID provId = UUID.randomUUID();
    RecommendationCandidateProjection provCand = mockCandidate(
        provId, null, "TIKTOK", "Sole Provisional KOC", 50_000L,
        BigDecimal.valueOf(15_000), BigDecimal.valueOf(750), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.70
    );

    com.influencermatch.backend.creator.repository.PostRepository mockPostRepo =
        mock(com.influencermatch.backend.creator.repository.PostRepository.class);
    com.influencermatch.backend.creator.model.Creator provEntity =
        com.influencermatch.backend.creator.model.Creator.builder().id(provId).build();
    com.influencermatch.backend.creator.model.Post p1 =
        com.influencermatch.backend.creator.model.Post.builder().creator(provEntity).views(15_000L).likes(750L).build();
    when(mockPostRepo.findByCreatorIdIn(any())).thenReturn(List.of(p1));
    service.setPostRepository(mockPostRepo);

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
        anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(provCand));

    RecommendationResponseDto response = service.recommendKocs(campaignId, 10);

    // Không có SCORE_READY -> recommendations rỗng (size 0)
    assertThat(response.getRecommendations()).isEmpty();

    // provisionalRecommendations có dữ liệu (size 1)
    assertThat(response.getProvisionalRecommendations()).hasSize(1);
    assertThat(response.getProvisionalRecommendations().get(0).getCreatorId()).isEqualTo(provId);
    assertThat(response.getProvisionalRecommendations().get(0).getIsProvisional()).isTrue();
  }

  @Test
  @DisplayName("Reviewer Test 2: Một Master Creator có hai Platform Accounts, một thuộc Main và một thuộc Provisional -> Master-aware deduplication xuyên hai pool")
  void testReviewerItem2_masterCreatorWithAccountsInBothPools_masterAwareDeduplicationAcrossPools() {
    UUID masterX = UUID.randomUUID();
    UUID readyId = UUID.randomUUID();
    UUID provId = UUID.randomUUID();
    UUID indepProvId = UUID.randomUUID();

    // Tài khoản 1 của Master X (TikTok, điểm cao, 3 bài views > 0) -> SCORE_READY
    RecommendationCandidateProjection readyCand = mockCandidate(
        readyId, masterX, "TIKTOK", "Master X TikTok", 100_000L,
        BigDecimal.valueOf(25_000), BigDecimal.valueOf(1_500), BigDecimal.valueOf(0.06),
        "Beauty", "Skincare", 0.85
    );

    // Tài khoản 2 của Master X (Instagram, chỉ 1 bài -> PROVISIONAL)
    RecommendationCandidateProjection provCand = mockCandidate(
        provId, masterX, "INSTAGRAM", "Master X Instagram", 60_000L,
        BigDecimal.valueOf(12_000), BigDecimal.valueOf(600), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.70
    );

    // KOC độc lập khác (chỉ 1 bài -> PROVISIONAL)
    RecommendationCandidateProjection indepCand = mockCandidate(
        indepProvId, null, "TIKTOK", "Independent Provisional KOC", 40_000L,
        BigDecimal.valueOf(10_000), BigDecimal.valueOf(500), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.65
    );

    com.influencermatch.backend.creator.repository.PostRepository mockPostRepo =
        mock(com.influencermatch.backend.creator.repository.PostRepository.class);
    com.influencermatch.backend.creator.model.Creator readyEntity =
        com.influencermatch.backend.creator.model.Creator.builder().id(readyId).build();
    com.influencermatch.backend.creator.model.Creator provEntity =
        com.influencermatch.backend.creator.model.Creator.builder().id(provId).build();
    com.influencermatch.backend.creator.model.Creator indepEntity =
        com.influencermatch.backend.creator.model.Creator.builder().id(indepProvId).build();

    when(mockPostRepo.findByCreatorIdIn(any())).thenReturn(List.of(
        // ready: 3 bài có views
        com.influencermatch.backend.creator.model.Post.builder().creator(readyEntity).views(20_000L).likes(1_000L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(readyEntity).views(22_000L).likes(1_100L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(readyEntity).views(25_000L).likes(1_250L).build(),
        // prov: 1 bài
        com.influencermatch.backend.creator.model.Post.builder().creator(provEntity).views(12_000L).likes(600L).build(),
        // indep: 1 bài
        com.influencermatch.backend.creator.model.Post.builder().creator(indepEntity).views(10_000L).likes(500L).build()
    ));
    service.setPostRepository(mockPostRepo);

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
        anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(readyCand, provCand, indepCand));

    RecommendationResponseDto response = service.recommendKocs(campaignId, 10);

    // 1. recommendations CHỈ chứa tài khoản TikTok của Master X (SCORE_READY)
    assertThat(response.getRecommendations()).hasSize(1);
    assertThat(response.getRecommendations().get(0).getCreatorId()).isEqualTo(readyId);
    assertThat(response.getRecommendations().get(0).getMasterId()).isEqualTo(masterX);

    // 2. provisionalRecommendations CHỈ chứa KOC độc lập
    // Tài khoản Instagram của Master X đã bị deduplicate xuyên pool (vì Master X đã xuất hiện ở Main)
    assertThat(response.getProvisionalRecommendations()).hasSize(1);
    assertThat(response.getProvisionalRecommendations().get(0).getCreatorId()).isEqualTo(indepProvId);

    // 3. Hai danh sách hoàn toàn không giao nhau theo cả creatorId lẫn masterId
    assertThat(response.getProvisionalRecommendations())
        .noneMatch(r -> masterX.equals(r.getMasterId()) || readyId.equals(r.getCreatorId()) || provId.equals(r.getCreatorId()));
  }

  @Test
  @DisplayName("Reviewer Test 3: Zero SCORE_READY, nhiều PROVISIONAL -> Sử dụng Business Fallback Weights và giới hạn đúng effectiveLimit")
  void testReviewerItem3_zeroScoreReadyCandidates_multipleProvisional_usesBusinessFallbackWeightsAndLimitsOutput() {
    campaign.setObjective(com.influencermatch.backend.campaign.enums.CampaignObjective.CONVERSION);

    // Tạo 4 ứng viên Provisional độc lập
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    UUID id3 = UUID.randomUUID();
    UUID id4 = UUID.randomUUID();

    RecommendationCandidateProjection c1 = mockCandidate(id1, null, "TIKTOK", "Prov KOC 1", 30_000L, BigDecimal.valueOf(10_000), BigDecimal.valueOf(500), BigDecimal.valueOf(0.05), "Beauty", "Skincare", 0.70);
    RecommendationCandidateProjection c2 = mockCandidate(id2, null, "TIKTOK", "Prov KOC 2", 35_000L, BigDecimal.valueOf(12_000), BigDecimal.valueOf(600), BigDecimal.valueOf(0.05), "Beauty", "Skincare", 0.75);
    RecommendationCandidateProjection c3 = mockCandidate(id3, null, "TIKTOK", "Prov KOC 3", 40_000L, BigDecimal.valueOf(14_000), BigDecimal.valueOf(700), BigDecimal.valueOf(0.05), "Beauty", "Skincare", 0.80);
    RecommendationCandidateProjection c4 = mockCandidate(id4, null, "TIKTOK", "Prov KOC 4", 45_000L, BigDecimal.valueOf(16_000), BigDecimal.valueOf(800), BigDecimal.valueOf(0.05), "Beauty", "Skincare", 0.85);

    com.influencermatch.backend.creator.repository.PostRepository mockPostRepo =
        mock(com.influencermatch.backend.creator.repository.PostRepository.class);
    // Mỗi ứng viên chỉ có 1 bài -> tất cả đều rơi vào PROVISIONAL (thiếu STAB)
    when(mockPostRepo.findByCreatorIdIn(any())).thenReturn(List.of(
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(id1).build()).views(10_000L).likes(500L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(id2).build()).views(12_000L).likes(600L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(id3).build()).views(14_000L).likes(700L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(id4).build()).views(16_000L).likes(800L).build()
    ));
    service.setPostRepository(mockPostRepo);

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
        anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(c1, c2, c3, c4));

    // Yêu cầu limit = 2
    RecommendationResponseDto response = service.recommendKocs(campaignId, 2);

    // 1. recommendations rỗng hoàn toàn do 0 ứng viên SCORE_READY
    assertThat(response.getRecommendations()).isEmpty();

    // 2. provisionalRecommendations bị giới hạn đúng effectiveLimit = 2 (thay vì trả về tràn lan cả 4)
    assertThat(response.getProvisionalRecommendations()).hasSize(2);

    // 3. Mỗi phần tử trong provisional được gắn nhãn sơ bộ rõ ràng
    for (var item : response.getProvisionalRecommendations()) {
      assertThat(item.getIsScoreReady()).isFalse();
      assertThat(item.getIsProvisional()).isTrue();
      assertThat(item.getFinalScore()).isNotNull().isBetween(0.0, 100.0);
    }
  }

  @Test
  @DisplayName("Integration Test: Khi AI microservice sinh Explanation thành công -> Toàn bộ Evidence Warnings vẫn được bảo toàn nguyên vẹn trong DTO")
  void testAiServiceExplanation_preservesAllEvidenceWarningsInFinalDto() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(
        creatorId, null, "TIKTOK", "AI Explanatory KOC", 25_000L,
        BigDecimal.valueOf(8_000), BigDecimal.valueOf(400), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.85
    );

    // Mock 3 bài đăng hợp lệ (đủ tính STAB -> SCORE_READY)
    com.influencermatch.backend.creator.repository.PostRepository mockPostRepo =
        mock(com.influencermatch.backend.creator.repository.PostRepository.class);
    when(mockPostRepo.findByCreatorIdIn(any())).thenReturn(List.of(
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(creatorId).build()).views(7_500L).likes(380L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(creatorId).build()).views(8_000L).likes(400L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(creatorId).build()).views(8_500L).likes(420L).build()
    ));
    service.setPostRepository(mockPostRepo);

    // Candidate chưa có lịch sử campaign (0 campaign -> sinh cảnh báo Cold-start)
    when(historicalCampaignRepository.findByCreatorId(creatorId)).thenReturn(Collections.emptyList());

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
        anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(cand));

    // AI microservice trả về explanation thành công
    when(aiServiceClient.getExplanation(any())).thenReturn(
        ExplainResponseDto.builder()
            .status("success")
            .summary("KOC có phong cách review tự nhiên, phù hợp với chiến dịch.")
            .reasons(List.of("ER 5.0% cao hơn trung bình ngành", "Phong cách chân thực"))
            .explanationAvailable(true)
            .build()
    );

    RecommendationResponseDto response = service.recommendKocs(campaignId, 10);
    assertThat(response.getRecommendations()).hasSize(1);

    var recItem = response.getRecommendations().get(0);
    assertThat(recItem.getReasoning()).isNotNull();
    assertThat(recItem.getReasoning().isExplanationAvailable()).isTrue();

    // 1. Kiểm chứng cấu trúc evidenceWarnings bảo toàn nguyên vẹn
    assertThat(recItem.getReasoning().getEvidenceWarnings()).isNotEmpty();
    assertThat(recItem.getReasoning().getEvidenceWarnings())
        .anyMatch(w -> w.contains("Chưa có kết quả chiến dịch quá khứ được xác minh"));

    // 2. Kiểm chứng danh sách reasons hiển thị kết hợp cả AI reasons lẫn Evidence Warnings
    assertThat(recItem.getReasoning().getReasons()).contains("ER 5.0% cao hơn trung bình ngành");
    assertThat(recItem.getReasoning().getReasons())
        .anyMatch(r -> r.startsWith("Lưu ý: ") && r.contains("Chưa có kết quả chiến dịch quá khứ được xác minh"));
  }

  @Test
  @DisplayName("P1 Test: AI Explanation trả về rỗng hoặc explanationAvailable=false -> Chuyển sang Deterministic Fallback và bảo toàn đầy đủ Evidence Warnings")
  void testAiServiceExplanation_blankOrUnavailable_triggersDeterministicFallback() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(
        creatorId, null, "TIKTOK", "Fallback KOC", 30_000L,
        BigDecimal.valueOf(10_000), BigDecimal.valueOf(500), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.85
    );

    com.influencermatch.backend.creator.repository.PostRepository mockPostRepo =
        mock(com.influencermatch.backend.creator.repository.PostRepository.class);
    when(mockPostRepo.findByCreatorIdIn(any())).thenReturn(List.of(
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(creatorId).build()).views(9_000L).likes(450L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(creatorId).build()).views(10_000L).likes(500L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(creatorId).build()).views(11_000L).likes(550L).build()
    ));
    service.setPostRepository(mockPostRepo);

    when(historicalCampaignRepository.findByCreatorId(creatorId)).thenReturn(Collections.emptyList());

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
        anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(cand));

    // AI response có explanationAvailable = false và summary rỗng
    when(aiServiceClient.getExplanation(any())).thenReturn(
        ExplainResponseDto.builder()
            .status("failure")
            .summary("")
            .explanationAvailable(false)
            .build()
    );

    RecommendationResponseDto response = service.recommendKocs(campaignId, 10);
    assertThat(response.getRecommendations()).hasSize(1);

    var recItem = response.getRecommendations().get(0);
    assertThat(recItem.getReasoning()).isNotNull();
    assertThat(recItem.getReasoning().isExplanationAvailable()).isFalse();

    // Phải sinh summary từ deterministic template
    assertThat(recItem.getReasoning().getSummary()).contains("Four-Memory TOPSIS");
    assertThat(recItem.getReasoning().getEvidenceWarnings()).isNotEmpty();
    assertThat(recItem.getReasoning().getEvidenceWarnings())
        .anyMatch(w -> w.contains("Chưa có kết quả chiến dịch quá khứ được xác minh"));
    assertThat(recItem.getReasoning().getReasons())
        .anyMatch(r -> r.startsWith("Lưu ý: ") && r.contains("Chưa có kết quả chiến dịch quá khứ được xác minh"));
  }

  @Test
  @DisplayName("P0 Integration: recommendKocs hỗ trợ DisplayMode (BEST_MATCH, DIVERSIFIED_MATCH, BUDGET_READY_FIRST)")
  void testRecommendKocs_supportsAllDisplayModes() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(
        creatorId, null, "TIKTOK", "Mode Test KOC", 30_000L,
        BigDecimal.valueOf(10_000), BigDecimal.valueOf(500), BigDecimal.valueOf(0.05),
        "Beauty", "Skincare", 0.85
    );

    com.influencermatch.backend.creator.repository.PostRepository mockPostRepo =
        mock(com.influencermatch.backend.creator.repository.PostRepository.class);
    when(mockPostRepo.findByCreatorIdIn(any())).thenReturn(List.of(
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(creatorId).build()).views(9_000L).likes(450L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(creatorId).build()).views(10_000L).likes(500L).build(),
        com.influencermatch.backend.creator.model.Post.builder().creator(com.influencermatch.backend.creator.model.Creator.builder().id(creatorId).build()).views(11_000L).likes(550L).build()
    ));
    service.setPostRepository(mockPostRepo);

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
        anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(cand));

    // Kiểm tra chạy thành công cả 3 DisplayMode
    RecommendationResponseDto r1 = service.recommendKocs(campaignId, 10, com.influencermatch.backend.recommendation.engine.presentation.enums.DisplayMode.BEST_MATCH);
    assertThat(r1.getRecommendations()).hasSize(1);

    RecommendationResponseDto r2 = service.recommendKocs(campaignId, 10, com.influencermatch.backend.recommendation.engine.presentation.enums.DisplayMode.DIVERSIFIED_MATCH);
    assertThat(r2.getRecommendations()).hasSize(1);

    RecommendationResponseDto r3 = service.recommendKocs(campaignId, 10, com.influencermatch.backend.recommendation.engine.presentation.enums.DisplayMode.BUDGET_READY_FIRST);
    assertThat(r3.getRecommendations()).hasSize(1);
  }

  private RecommendationCandidateProjection mockCandidate(
      UUID creatorId, UUID masterId, String platform, String displayName, Long followers,
      BigDecimal avgViews, BigDecimal avgLikes, BigDecimal er, String niche, String cat, Double semantic) {
    RecommendationCandidateProjection m = mock(RecommendationCandidateProjection.class);
    when(m.getCreatorId()).thenReturn(creatorId);
    when(m.getMasterId()).thenReturn(masterId);
    when(m.getPlatform()).thenReturn(platform);
    when(m.getDisplayName()).thenReturn(displayName);
    when(m.getUserName()).thenReturn(displayName.toLowerCase().replace(" ", ""));
    when(m.getFollowers()).thenReturn(followers);
    when(m.getAvgViews()).thenReturn(avgViews);
    when(m.getAvgLikes()).thenReturn(avgLikes);
    when(m.getEngagementRate()).thenReturn(er);
    when(m.getNiche()).thenReturn(niche);
    when(m.getCategory()).thenReturn(cat);
    when(m.getCreatorType()).thenReturn("KOC");
    when(m.getSemanticScore()).thenReturn(semantic);
    when(m.getAccountStatus()).thenReturn("ACTIVE");
    return m;
  }
}
