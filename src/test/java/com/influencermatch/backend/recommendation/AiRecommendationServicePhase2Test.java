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
        aiServiceClient
    );

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
        BigDecimal.valueOf(10_000), BigDecimal.valueOf(200), BigDecimal.valueOf(2.0),
        "Beauty", "Cosmetics", 0.70
    );

    // Kênh 2: Instagram cùng KOC nhưng ER và semantic cao hơn
    RecommendationCandidateProjection igCand = mockCandidate(
        instagramId, masterId, "INSTAGRAM", "Trang Dao IG", 80_000L,
        BigDecimal.valueOf(20_000), BigDecimal.valueOf(1_500), BigDecimal.valueOf(7.5),
        "Beauty", "Skincare", 0.82
    );

    // KOC khác độc lập
    UUID otherId = UUID.randomUUID();
    RecommendationCandidateProjection otherCand = mockCandidate(
        otherId, null, "TIKTOK", "Lan Anh Review", 30_000L,
        BigDecimal.valueOf(15_000), BigDecimal.valueOf(900), BigDecimal.valueOf(6.0),
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
        BigDecimal.valueOf(10_000), BigDecimal.valueOf(500), BigDecimal.valueOf(5.0),
        "Beauty", "Skincare", 0.60
    );

    when(historicalCampaignRepository.findByCreatorId(creatorId)).thenReturn(Collections.emptyList());

    ScoreBreakdownDto score = service.calculateFourMemoryScores(cand, campaign, brandProfileId);

    assertThat(score.getM1Score()).isEqualTo(50.0);
  }

  @Test
  @DisplayName("Follower Score Math: Log10 Asymmetric Scoring with floor at 60 for oversized creator")
  void testFollowerScore_mathFormulas() {
    UUID normalId = UUID.randomUUID();
    RecommendationCandidateProjection insideRange = mockCandidate(normalId, null, "TIKTOK", "Fit", 50_000L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "Beauty", "Beauty", 0.5);
    ScoreBreakdownDto scoreInside = service.calculateFourMemoryScores(insideRange, campaign, brandProfileId);
    assertThat(scoreInside.getFollowerScore()).isEqualTo(100.0);

    UUID hugeId = UUID.randomUUID(); // 5 triệu followers (trần là 500k)
    RecommendationCandidateProjection overRange = mockCandidate(hugeId, null, "TIKTOK", "Huge", 5_000_000L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "Beauty", "Beauty", 0.5);
    ScoreBreakdownDto scoreOver = service.calculateFourMemoryScores(overRange, campaign, brandProfileId);
    // log10(5M / 500k) = log10(10) = 1.0 -> 100 - 30 = 70.0
    assertThat(scoreOver.getFollowerScore()).isEqualTo(70.0);

    UUID superHugeId = UUID.randomUUID(); // 100 triệu followers -> sàn 60.0
    RecommendationCandidateProjection superHuge = mockCandidate(superHugeId, null, "TIKTOK", "Celeb", 100_000_000L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "Beauty", "Beauty", 0.5);
    ScoreBreakdownDto scoreSuperHuge = service.calculateFourMemoryScores(superHuge, campaign, brandProfileId);
    assertThat(scoreSuperHuge.getFollowerScore()).isEqualTo(60.0);
  }

  @Test
  @DisplayName("Circuit Breaker Fallback: Khi Python AI explain lỗi, trả về thẻ mặc định với explanationAvailable = false")
  void testCircuitBreaker_fallbackWhenAiUnavailable() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(
        creatorId, null, "TIKTOK", "Mai Anh", 20_000L,
        BigDecimal.valueOf(10_000), BigDecimal.valueOf(500), BigDecimal.valueOf(5.0),
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
    return m;
  }
}
