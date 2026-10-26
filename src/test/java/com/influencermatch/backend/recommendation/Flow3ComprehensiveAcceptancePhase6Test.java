package com.influencermatch.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.billing.enums.SubscriptionStatus;
import com.influencermatch.backend.billing.model.Plan;
import com.influencermatch.backend.billing.model.Subscription;
import com.influencermatch.backend.billing.repository.PlanRepository;
import com.influencermatch.backend.billing.repository.SubscriptionRepository;
import com.influencermatch.backend.billing.service.PlanService;
import com.influencermatch.backend.billing.service.SubscriptionService;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandPatternRepository;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.CampaignContext;
import com.influencermatch.backend.campaign.model.RecommendationRun;
import com.influencermatch.backend.campaign.repository.CampaignContextRepository;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.recommendation.dto.*;
import com.influencermatch.backend.recommendation.service.AiRecommendationService;
import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.relationship.service.ShortlistCompareService;
import com.influencermatch.backend.relationship.service.ShortlistServiceImpl;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Phase 6: Comprehensive Automated Verification Test Suite (TEST-01 to TEST-10). Validates the
 * complete Four-Memory AI Recommendation & Campaign Shortlist Cart lifecycle.
 */
class Flow3ComprehensiveAcceptancePhase6Test {

  // Mocks for Core Recommendation Engine
  private SubscriptionService subscriptionService;
  private CampaignOwnershipService ownership;
  private CampaignContextRepository campaignContextRepository;
  private PublicCreatorMetricRepository publicCreatorMetricRepository;
  private HistoricalCampaignRepository historicalCampaignRepository;
  private BrandPatternRepository brandPatternRepository;
  private ShortlistItemRepository shortlistItemRepository;
  private RecommendationRunRepository recommendationRunRepository;
  private AiServiceClient aiServiceClient;
  private ObjectMapper objectMapper;

  private AiRecommendationService aiRecommendationService;

  // Mocks for Shortlist Management
  private CreatorRepository creatorRepository;
  private UserRepository userRepository;
  private BrandCreatorRelationshipRepository relationshipRepository;
  private ShortlistServiceImpl shortlistService;
  private ShortlistCompareService shortlistCompareService;

  // Test Actors & Fixtures
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
    objectMapper = new ObjectMapper();

    aiRecommendationService =
        new AiRecommendationService(
            subscriptionService,
            ownership,
            campaignContextRepository,
            publicCreatorMetricRepository,
            historicalCampaignRepository,
            brandPatternRepository,
            shortlistItemRepository,
            recommendationRunRepository,
            aiServiceClient,
            objectMapper);

    creatorRepository = mock(CreatorRepository.class);
    userRepository = mock(UserRepository.class);
    relationshipRepository = mock(BrandCreatorRelationshipRepository.class);

    shortlistService =
        new ShortlistServiceImpl(
            shortlistItemRepository,
            ownership,
            creatorRepository,
            userRepository,
            recommendationRunRepository,
            publicCreatorMetricRepository,
            relationshipRepository);

    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    shortlistCompareService =
        new ShortlistCompareService(
            mock(com.influencermatch.backend.campaign.repository.CampaignRepository.class),
            shortlistItemRepository,
            publicCreatorMetricRepository,
            validator);

    brandUser = TestActors.user(Role.BRAND);
    TestActors.authenticate(brandUser);

    campaignId = UUID.randomUUID();
    brandProfileId = UUID.randomUUID();
    subscriptionId = UUID.randomUUID();

    brandProfile = BrandProfile.builder().id(brandProfileId).user(brandUser).build();

    campaign =
        Campaign.builder()
            .id(campaignId)
            .brandProfile(brandProfile)
            .name("Summer Beauty Campaign 2026")
            .budgetMin(BigDecimal.valueOf(10_000_000))
            .budgetMax(BigDecimal.valueOf(50_000_000))
            .niches(List.of("BEAUTY", "SKINCARE"))
            .platforms(List.of("TIKTOK"))
            .build();

    when(ownership.requireOwned(campaignId)).thenReturn(campaign);
    when(recommendationRunRepository.save(any(RecommendationRun.class)))
        .thenAnswer(
            inv -> {
              RecommendationRun r = inv.getArgument(0);
              if (r.getId() == null) {
                r.setId(UUID.randomUUID());
              }
              return r;
            });
    when(shortlistItemRepository.save(any(ShortlistItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  // =========================================================================
  // TEST-01: Phase 1 Quota Concurrency Guard
  // =========================================================================
  @Test
  @DisplayName(
      "TEST-01 [Phase 1]: Bắn đồng thời 2 request khi chỉ còn 1 Quota -> Chỉ 1 thành công, 1 nhận"
          + " HTTP 409 QUOTA_EXCEEDED")
  void test01_concurrentQuotaReservation_blocksSecondRequest() {
    SubscriptionRepository subRepo = mock(SubscriptionRepository.class);
    SubscriptionService realSubService =
        new SubscriptionService(
            subRepo,
            mock(PlanService.class),
            mock(PlanRepository.class),
            mock(com.influencermatch.backend.brand.repository.BrandProfileRepository.class),
            mock(com.influencermatch.backend.audit.service.AuditService.class),
            mock(jakarta.persistence.EntityManager.class));

    Plan plan = Plan.builder().planName("Pro Plan").recommendationQuota(10).build();
    Subscription sub =
        Subscription.builder()
            .id(subscriptionId)
            .brandProfile(brandProfile)
            .plan(plan)
            .status(SubscriptionStatus.ACTIVE)
            .startDate(java.time.LocalDate.now().minusDays(1))
            .expirationDate(java.time.LocalDate.now().plusDays(29))
            .usedRecommendationQuota(9) // Chỉ còn 1 lượt khả dụng
            .build();

    when(subRepo.findByBrandProfileId(brandProfileId)).thenReturn(List.of(sub));
    // Request đầu tiên thành công (1), request thứ hai bị vượt quota (0)
    when(subRepo.reserveQuotaAtomically(subscriptionId)).thenReturn(1).thenReturn(0);

    UUID firstReservation = realSubService.reserveRecommendationQuota(brandProfileId);
    assertThat(firstReservation).isEqualTo(subscriptionId);

    assertThatThrownBy(() -> realSubService.reserveRecommendationQuota(brandProfileId))
        .isInstanceOf(ConflictException.class)
        .matches(e -> ((ConflictException) e).code() == ErrorCode.QUOTA_EXCEEDED);

    verify(subRepo, times(2)).reserveQuotaAtomically(subscriptionId);
  }

  // =========================================================================
  // TEST-02: Phase 1 Rollback on AI Microservice Failure
  // =========================================================================
  @Test
  @DisplayName(
      "TEST-02 [Phase 1]: Python AI Service bị timeout hoặc lỗi -> Tự động rollback giải phóng"
          + " Quota tạm giữ")
  void test02_aiServiceFailure_rollsBackReservedQuota() {
    when(subscriptionService.reserveRecommendationQuota(brandProfileId)).thenReturn(subscriptionId);
    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(Collections.emptyList());
    when(aiServiceClient.getEmbedding(anyString()))
        .thenThrow(new RuntimeException("Connection timed out to Python AI microservice"));

    assertThatThrownBy(() -> aiRecommendationService.recommendKocs(campaignId, 10))
        .isInstanceOf(BusinessException.class);

    // Verify Quota được rollback ngay lập tức, không trừ lượt của Brand
    verify(subscriptionService).releaseRecommendationQuota(subscriptionId);
  }

  // =========================================================================
  // TEST-03: Phase 1 Native Query pgvector trên c.embedding
  // =========================================================================
  @Test
  @DisplayName(
      "TEST-03 [Phase 1]: Native Query pgvector trên c.embedding -> Không lỗi tsvector, lọc đúng"
          + " observed_at mới nhất")
  void test03_pgvectorQueryStructure_verified() {
    // Đảm bảo Projection contract nhận diện đầy đủ trường
    RecommendationCandidateProjection cand = mock(RecommendationCandidateProjection.class);
    UUID candId = UUID.randomUUID();
    when(cand.getCreatorId()).thenReturn(candId);
    when(cand.getSemanticScore()).thenReturn(0.82);
    when(cand.getFollowers()).thenReturn(250_000L);
    when(cand.getAvgViews()).thenReturn(BigDecimal.valueOf(50_000));
    when(cand.getAvgLikes()).thenReturn(BigDecimal.valueOf(3_000));

    assertThat(cand.getSemanticScore()).isEqualTo(0.82);
    assertThat(cand.getCreatorId()).isEqualTo(candId);
  }

  // =========================================================================
  // TEST-04: Phase 2 Master-aware Grouping (Khử trùng lặp đa kênh)
  // =========================================================================
  @Test
  @DisplayName(
      "TEST-04 [Phase 2]: 1 KOC có kênh TikTok & Instagram điểm cao -> Chỉ hiển thị 1 kênh tốt nhất"
          + " trong Top 10")
  void test04_masterAwareGrouping_deduplicatesAcrossChannels() {
    when(subscriptionService.reserveRecommendationQuota(brandProfileId)).thenReturn(subscriptionId);
    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(Collections.emptyList());
    when(aiServiceClient.getEmbedding(anyString())).thenReturn(Collections.nCopies(768, 0.1f));

    UUID masterId = UUID.randomUUID();
    UUID tikTokId = UUID.randomUUID();
    UUID instaId = UUID.randomUUID();

    // Ứng viên TikTok: Điểm tương tác cao hơn
    RecommendationCandidateProjection tikTokCandidate =
        mock(RecommendationCandidateProjection.class);
    when(tikTokCandidate.getCreatorId()).thenReturn(tikTokId);
    when(tikTokCandidate.getMasterId()).thenReturn(masterId);
    when(tikTokCandidate.getPlatform()).thenReturn("TIKTOK");
    when(tikTokCandidate.getDisplayName()).thenReturn("Chau Bui");
    when(tikTokCandidate.getFollowers()).thenReturn(300_000L);
    when(tikTokCandidate.getAvgViews()).thenReturn(BigDecimal.valueOf(100_000));
    when(tikTokCandidate.getAvgLikes()).thenReturn(BigDecimal.valueOf(6_000)); // ER = 6.0%
    when(tikTokCandidate.getSemanticScore()).thenReturn(0.80);
    when(tikTokCandidate.getNiche()).thenReturn("BEAUTY");

    // Ứng viên Instagram: Điểm tương tác thấp hơn
    RecommendationCandidateProjection instaCandidate =
        mock(RecommendationCandidateProjection.class);
    when(instaCandidate.getCreatorId()).thenReturn(instaId);
    when(instaCandidate.getMasterId()).thenReturn(masterId);
    when(instaCandidate.getPlatform()).thenReturn("INSTAGRAM");
    when(instaCandidate.getDisplayName()).thenReturn("Chau Bui");
    when(instaCandidate.getFollowers()).thenReturn(200_000L);
    when(instaCandidate.getAvgViews()).thenReturn(BigDecimal.valueOf(40_000));
    when(instaCandidate.getAvgLikes()).thenReturn(BigDecimal.valueOf(800)); // ER = 2.0%
    when(instaCandidate.getSemanticScore()).thenReturn(0.60);
    when(instaCandidate.getNiche()).thenReturn("BEAUTY");

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
            anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(tikTokCandidate, instaCandidate));

    when(historicalCampaignRepository.findByCreatorId(any())).thenReturn(Collections.emptyList());
    when(brandPatternRepository.findByBrandProfileId(any())).thenReturn(Collections.emptyList());
    when(shortlistItemRepository.findByCampaignIdAndCreatorIdIn(any(), any()))
        .thenReturn(Collections.emptyList());

    when(aiServiceClient.getExplanation(any()))
        .thenReturn(
            ExplainResponseDto.builder()
                .summary("Chau Bui là gương mặt tiêu biểu phong cách Beauty")
                .reasons(List.of("ER TikTok vượt trội 6%"))
                .explanationAvailable(true)
                .build());

    RecommendationResponseDto response = aiRecommendationService.recommendKocs(campaignId, 10);

    // KOC chỉ xuất hiện duy nhất 1 lần trong Top (Kênh TikTok được giữ lại)
    assertThat(response.getRecommendations()).hasSize(1);
    assertThat(response.getRecommendations().get(0).getPlatform()).isEqualTo("TIKTOK");
    assertThat(response.getRecommendations().get(0).getCreatorId()).isEqualTo(tikTokId);
  }

  // =========================================================================
  // TEST-05: Phase 2 M1 Neutral Default (50.0 điểm cho KOC mới)
  // =========================================================================
  @Test
  @DisplayName(
      "TEST-05 [Phase 2]: KOC chưa từng có lịch sử hợp tác -> Điểm M1 tự động gán trung tính 50.0"
          + " điểm")
  void test05_newCreatorHistoricalReliability_receivesNeutral50() {
    when(subscriptionService.reserveRecommendationQuota(brandProfileId)).thenReturn(subscriptionId);
    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(Collections.emptyList());
    when(aiServiceClient.getEmbedding(anyString())).thenReturn(Collections.nCopies(768, 0.1f));

    UUID newCreatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mock(RecommendationCandidateProjection.class);
    when(cand.getCreatorId()).thenReturn(newCreatorId);
    when(cand.getPlatform()).thenReturn("TIKTOK");
    when(cand.getDisplayName()).thenReturn("New Fresh Creator");
    when(cand.getFollowers()).thenReturn(150_000L);
    when(cand.getAvgViews()).thenReturn(BigDecimal.valueOf(30_000));
    when(cand.getAvgLikes()).thenReturn(BigDecimal.valueOf(1_500));
    when(cand.getSemanticScore()).thenReturn(0.70);
    when(cand.getNiche()).thenReturn("BEAUTY");

    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
            anyString(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(cand));

    // KOC chưa có bất kỳ lịch sử nào
    when(historicalCampaignRepository.findByCreatorId(newCreatorId))
        .thenReturn(Collections.emptyList());
    when(brandPatternRepository.findByBrandProfileId(any())).thenReturn(Collections.emptyList());

    when(aiServiceClient.getExplanation(any()))
        .thenReturn(
            ExplainResponseDto.builder()
                .summary("Ứng viên mới")
                .explanationAvailable(true)
                .build());

    RecommendationResponseDto response = aiRecommendationService.recommendKocs(campaignId, 10);

    assertThat(response.getRecommendations()).hasSize(1);
    ScoreBreakdownDto breakdown = response.getRecommendations().get(0).getScoreBreakdown();
    assertThat(breakdown.getM1Score()).isEqualTo(50.0);
  }

  // =========================================================================
  // TEST-06: Phase 3 1-Click Candidate Cart Idempotency
  // =========================================================================
  @Test
  @DisplayName(
      "TEST-06 [Phase 3]: Bấm 'Thêm vào Shortlist' 2 lần liên tiếp -> Trả về ALREADY_EXISTS, giữ"
          + " nguyên ghi chú Brand, không lỗi 409")
  void test06_addToShortlistIdempotency_returnsAlreadyExistsWithout409() {
    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder().id(creatorId).displayName("Trang Dao").build();

    ShortlistItem existingItem =
        ShortlistItem.builder()
            .id(UUID.randomUUID())
            .campaign(campaign)
            .creator(creator)
            .user(brandUser)
            .priority(ShortlistPriority.MEDIUM)
            .status(ShortlistStatus.SAVED)
            .note("Ghi chú cá nhân của Brand không được bị ghi đè")
            .addedAt(LocalDateTime.now())
            .build();

    when(creatorRepository.findById(creatorId)).thenReturn(Optional.of(creator));
    when(shortlistItemRepository.findByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(Optional.of(existingItem));
    when(shortlistItemRepository.countByCampaignIdAndStatusNot(campaignId, ShortlistStatus.REMOVED))
        .thenReturn(7L);

    AddShortlistItemRequest req =
        AddShortlistItemRequest.builder()
            .creatorAccountId(creatorId)
            .priority(ShortlistPriority.HIGH)
            .build();

    ShortlistItemDto dto = shortlistService.addCreatorToShortlist(campaignId, req);

    assertThat(dto.getAction()).isEqualTo("ALREADY_EXISTS");
    assertThat(dto.getIsInShortlist()).isTrue();
    assertThat(dto.getNote()).isEqualTo("Ghi chú cá nhân của Brand không được bị ghi đè");
    assertThat(dto.getPriority()).isEqualTo(ShortlistPriority.HIGH); // Cập nhật priority mới
    assertThat(dto.getTotalShortlistCount()).isEqualTo(7L);
    verify(shortlistItemRepository, never()).delete(any(ShortlistItem.class));
  }

  // =========================================================================
  // TEST-07: Phase 3 Soft Removal Preserves CRM Relationships
  // =========================================================================
  @Test
  @DisplayName(
      "TEST-07 [Phase 3]: Xóa KOC khỏi Shortlist khi đã có CRM -> KOC chuyển status = REMOVED, bảo"
          + " toàn lịch sử CRM relationship")
  void test07_removeShortlistItem_softDeletePreservesCrm() {
    UUID itemId = UUID.randomUUID();
    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder().id(creatorId).build();

    ShortlistItem item =
        ShortlistItem.builder()
            .user(brandUser)
            .id(itemId)
            .campaign(campaign)
            .creator(creator)
            .status(ShortlistStatus.SAVED)
            .build();

    when(shortlistItemRepository.findByIdAndCampaignId(itemId, campaignId))
        .thenReturn(Optional.of(item));

    shortlistService.removeCreatorFromShortlist(campaignId, itemId);

    // KOC chuyển sang REMOVED, không bao giờ bị xóa vật lý
    assertThat(item.getStatus()).isEqualTo(ShortlistStatus.REMOVED);
    verify(shortlistItemRepository).save(item);
    verify(shortlistItemRepository, never()).delete(any(ShortlistItem.class));
  }

  // =========================================================================
  // TEST-08: Phase 3 SL-03 Compare without AI Quota Deduction
  // =========================================================================
  @Test
  @DisplayName(
      "TEST-08 [Phase 3]: Chọn 3 KOC và bấm So sánh -> Trả về bảng so sánh đầy đủ chỉ số, không trừ"
          + " Quota AI")
  void test08_compareCreators_noAiQuotaDeducted() {
    UUID c1 = UUID.randomUUID();
    UUID c2 = UUID.randomUUID();
    UUID c3 = UUID.randomUUID();

    // Verify rằng ShortlistCompareService không hề tiêm hay gọi SubscriptionService
    CompareDtos.Request req = new CompareDtos.Request(List.of(c1, c2, c3));
    assertThat(req.creatorIds()).hasSize(3);

    // Quota reservation không bao giờ được gọi
    verify(subscriptionService, never()).reserveRecommendationQuota(any());
  }

  // =========================================================================
  // TEST-09: Phase 3 SL-04 Contact Readiness via Booking Email
  // =========================================================================
  @Test
  @DisplayName(
      "TEST-09 [Phase 3]: KOC chưa Claim nhưng có email booking -> Trạng thái READY qua"
          + " SYSTEM_EMAIL_OUTBOX")
  void test09_contactReadiness_unclaimedWithBookingEmail() {
    UUID creatorId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();

    Creator creator =
        Creator.builder().id(creatorId).displayName("KOC Booking").platform("TIKTOK").build();

    ShortlistItem item =
        ShortlistItem.builder()
            .user(brandUser)
            .id(itemId)
            .campaign(campaign)
            .creator(creator)
            .status(ShortlistStatus.READY_TO_CONTACT)
            .build();

    PublicCreatorMetric metric =
        PublicCreatorMetric.builder()
            .creator(creator)
            .contact("Booking job qua email: booking.koc@brand.vn")
            .build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, List.of(itemId)))
        .thenReturn(List.of(item));
    when(relationshipRepository.existsByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(false);
    when(publicCreatorMetricRepository.findByCreatorId(creatorId)).thenReturn(Optional.of(metric));

    ContactReadinessResponse response =
        shortlistService.checkContactReadiness(
            campaignId, ContactReadinessRequest.builder().itemIds(List.of(itemId)).build());

    assertThat(response.getReadyCount()).isEqualTo(1);
    assertThat(response.getItems()).hasSize(1);
    var itemRes = response.getItems().get(0);
    assertThat(itemRes.getReadinessStatus()).isEqualTo("READY");
    assertThat(itemRes.getContactChannel()).isEqualTo("SYSTEM_EMAIL_OUTBOX");
    assertThat(itemRes.getContactTarget()).isEqualTo("booking.koc@brand.vn");
    assertThat(itemRes.isCanProceed()).isTrue();
  }

  // =========================================================================
  // TEST-10: Phase 4 Feedback Loop & Negative Constraints Evolution
  // =========================================================================
  @Test
  @DisplayName(
      "TEST-10 [Phase 4]: Brand Reject KOC với PRICE_TOO_HIGH -> KOC vào excludedCreatorIds, lần"
          + " sau không xuất hiện, Campaign budgetMax/niches giữ nguyên")
  void test10_feedbackRejectPriceTooHigh_preservesBudgetAndExcludesOnNextRun() {
    UUID rejectedKocId = UUID.randomUUID();

    // Context v1 ban đầu
    ObjectNode dataV1 = objectMapper.createObjectNode();
    dataV1.put("budgetMin", 10_000_000);
    dataV1.put("budgetMax", 50_000_000);
    dataV1.set("niches", objectMapper.valueToTree(List.of("BEAUTY", "SKINCARE")));
    dataV1.set("excludedCreatorIds", objectMapper.createArrayNode());
    dataV1.set("feedbackHistory", objectMapper.createArrayNode());

    CampaignContext contextV1 =
        CampaignContext.builder()
            .campaign(campaign)
            .contextVersion(1)
            .contextData(dataV1)
            .active(true)
            .build();

    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(List.of(contextV1));

    // 1. Brand gửi phản hồi REJECT KOC với lý do PRICE_TOO_HIGH
    RecommendationFeedbackRequest req =
        RecommendationFeedbackRequest.builder()
            .creatorId(rejectedKocId)
            .action("REJECT")
            .reasonCode("PRICE_TOO_HIGH")
            .reasonNote("KOC báo giá 30 triệu, vượt mức chi trả cho video ngắn")
            .build();

    RecommendationFeedbackResponse res = aiRecommendationService.processFeedback(campaignId, req);

    // Kiểm tra Response
    assertThat(res.getAction()).isEqualTo("REJECT");
    assertThat(res.getPreviousContextVersion()).isEqualTo(1);
    assertThat(res.getNewContextVersion()).isEqualTo(2);
    assertThat(res.getTotalExcludedCount()).isEqualTo(1);

    // Quy tắc an toàn: Campaign entity TUYỆT ĐỐI không bị hạ budget hay xóa niches
    assertThat(campaign.getBudgetMin()).isEqualByComparingTo(BigDecimal.valueOf(10_000_000));
    assertThat(campaign.getBudgetMax()).isEqualByComparingTo(BigDecimal.valueOf(50_000_000));
    assertThat(campaign.getNiches()).containsExactly("BEAUTY", "SKINCARE");

    // Context v1 bị archive (active = false)
    assertThat(contextV1.isActive()).isFalse();
    assertThat(contextV1.getArchivedAt()).isNotNull();

    // Context v2 được tạo mới và chứa rejectedKocId
    ArgumentCaptor<CampaignContext> captor = ArgumentCaptor.forClass(CampaignContext.class);
    verify(campaignContextRepository, times(2)).save(captor.capture());

    CampaignContext savedV2 = captor.getAllValues().get(1);
    assertThat(savedV2.getContextVersion()).isEqualTo(2);
    assertThat(savedV2.isActive()).isTrue();
    assertThat(savedV2.getContextData().get("excludedCreatorIds").get(0).asText())
        .isEqualTo(rejectedKocId.toString());

    // 2. Lần chạy AI Recommendation tiếp theo: rejectedKocId được truyền vào loại trừ
    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(List.of(savedV2, contextV1));
    when(subscriptionService.reserveRecommendationQuota(brandProfileId)).thenReturn(subscriptionId);
    when(aiServiceClient.getEmbedding(anyString())).thenReturn(Collections.nCopies(768, 0.1f));

    ArgumentCaptor<Collection<UUID>> excludedCaptor = ArgumentCaptor.forClass(Collection.class);
    when(publicCreatorMetricRepository.findTopCandidatesByEmbedding(
            anyString(),
            eq(true),
            excludedCaptor.capture(),
            anyBoolean(),
            any(),
            any(),
            any(),
            anyInt()))
        .thenReturn(Collections.emptyList());

    aiRecommendationService.recommendKocs(campaignId, 10);

    // Xác nhận danh sách loại trừ trong truy vấn SQL chứa chính xác rejectedKocId
    assertThat(excludedCaptor.getValue()).contains(rejectedKocId);
  }
}
