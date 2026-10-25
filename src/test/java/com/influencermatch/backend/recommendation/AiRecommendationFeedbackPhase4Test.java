package com.influencermatch.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.billing.service.SubscriptionService;
import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandPatternRepository;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.CampaignContext;
import com.influencermatch.backend.campaign.repository.CampaignContextRepository;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.recommendation.dto.RecommendationFeedbackRequest;
import com.influencermatch.backend.recommendation.dto.RecommendationFeedbackResponse;
import com.influencermatch.backend.recommendation.service.AiRecommendationService;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

class AiRecommendationFeedbackPhase4Test {

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

  private AiRecommendationService service;

  private User brandUser;
  private BrandProfile brandProfile;
  private Campaign campaign;
  private UUID campaignId;
  private UUID brandProfileId;

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
        objectMapper
    );

    brandUser = TestActors.user(Role.BRAND);
    TestActors.authenticate(brandUser);

    campaignId = UUID.randomUUID();
    brandProfileId = UUID.randomUUID();

    brandProfile = BrandProfile.builder()
        .id(brandProfileId)
        .user(brandUser)
        .build();

    campaign = Campaign.builder()
        .id(campaignId)
        .brandProfile(brandProfile)
        .name("Beauty Summer Campaign 2026")
        .budgetMin(BigDecimal.valueOf(10_000_000))
        .budgetMax(BigDecimal.valueOf(50_000_000))
        .niches(List.of("BEAUTY", "SKINCARE"))
        .platforms(List.of("TIKTOK"))
        .build();

    when(ownership.requireOwned(campaignId)).thenReturn(campaign);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("TEST-10: Brand rejects KOC with PRICE_TOO_HIGH -> Excludes KOC in context v2, archives v1, and preserves Campaign budget/niches")
  void testFeedback_rejectPriceTooHigh_preservesBudgetAndExcludesCreator() {
    UUID creatorId = UUID.randomUUID();

    // Context v1 ban đầu
    ObjectNode initialData = objectMapper.createObjectNode();
    initialData.put("budgetMin", 10_000_000);
    initialData.put("budgetMax", 50_000_000);
    initialData.set("niches", objectMapper.valueToTree(List.of("BEAUTY", "SKINCARE")));
    initialData.set("excludedCreatorIds", objectMapper.createArrayNode());
    initialData.set("feedbackHistory", objectMapper.createArrayNode());

    CampaignContext contextV1 = CampaignContext.builder()
        .id(UUID.randomUUID())
        .campaign(campaign)
        .contextVersion(1)
        .contextData(initialData)
        .active(true)
        .build();

    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(List.of(contextV1));

    RecommendationFeedbackRequest req = RecommendationFeedbackRequest.builder()
        .creatorId(creatorId)
        .action("REJECT")
        .reasonCode("PRICE_TOO_HIGH")
        .reasonNote("KOC báo giá 30 triệu, vượt mức chi trả cho video ngắn")
        .build();

    RecommendationFeedbackResponse res = service.processFeedback(campaignId, req);

    // 1. Kiểm tra Response
    assertThat(res.getCampaignId()).isEqualTo(campaignId);
    assertThat(res.getCreatorId()).isEqualTo(creatorId);
    assertThat(res.getAction()).isEqualTo("REJECT");
    assertThat(res.getReasonCode()).isEqualTo("PRICE_TOO_HIGH");
    assertThat(res.getPreviousContextVersion()).isEqualTo(1);
    assertThat(res.getNewContextVersion()).isEqualTo(2);
    assertThat(res.getTotalExcludedCount()).isEqualTo(1);

    // 2. Quy tắc an toàn: Campaign entity TUYỆT ĐỐI không bị hạ budget hay xóa niches
    assertThat(campaign.getBudgetMin()).isEqualByComparingTo(BigDecimal.valueOf(10_000_000));
    assertThat(campaign.getBudgetMax()).isEqualByComparingTo(BigDecimal.valueOf(50_000_000));
    assertThat(campaign.getNiches()).containsExactly("BEAUTY", "SKINCARE");

    // 3. Kiểm tra Context v1 bị archive
    assertThat(contextV1.isActive()).isFalse();
    assertThat(contextV1.getArchivedAt()).isNotNull();
    verify(campaignContextRepository).save(contextV1);

    // 4. Kiểm tra Context v2 mới được lưu với bối cảnh tiến hóa
    ArgumentCaptor<CampaignContext> contextCaptor = ArgumentCaptor.forClass(CampaignContext.class);
    verify(campaignContextRepository, times(2)).save(contextCaptor.capture());

    CampaignContext savedV2 = contextCaptor.getAllValues().get(1);
    assertThat(savedV2.getContextVersion()).isEqualTo(2);
    assertThat(savedV2.isActive()).isTrue();

    JsonNode v2Data = savedV2.getContextData();
    assertThat(v2Data.get("excludedCreatorIds")).hasSize(1);
    assertThat(v2Data.get("excludedCreatorIds").get(0).asText()).isEqualTo(creatorId.toString());

    assertThat(v2Data.get("feedbackHistory")).hasSize(1);
    JsonNode feedbackEntry = v2Data.get("feedbackHistory").get(0);
    assertThat(feedbackEntry.get("creatorId").asText()).isEqualTo(creatorId.toString());
    assertThat(feedbackEntry.get("action").asText()).isEqualTo("REJECT");
    assertThat(feedbackEntry.get("reasonCode").asText()).isEqualTo("PRICE_TOO_HIGH");
    assertThat(feedbackEntry.get("reasonNote").asText()).contains("vượt mức chi trả");

    // 5. Kiểm tra M4 BrandPattern được ghi nhận
    verify(brandPatternRepository).save(any(BrandPattern.class));
  }

  @Test
  @DisplayName("TEST-10 Continuation: Sequential rejections evolve context versions v1 -> v2 -> v3 accumulating exclusions")
  void testFeedback_sequentialRejections_accumulateExclusions() {
    UUID kocA = UUID.randomUUID();
    UUID kocB = UUID.randomUUID();

    // Context v1 ban đầu
    ObjectNode dataV1 = objectMapper.createObjectNode();
    dataV1.set("excludedCreatorIds", objectMapper.createArrayNode());
    dataV1.set("feedbackHistory", objectMapper.createArrayNode());

    CampaignContext contextV1 = CampaignContext.builder()
        .campaign(campaign)
        .contextVersion(1)
        .contextData(dataV1)
        .active(true)
        .build();

    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(List.of(contextV1));

    // Bước 1: Reject KOC A -> tiến hóa lên v2
    RecommendationFeedbackRequest reqA = RecommendationFeedbackRequest.builder()
        .creatorId(kocA)
        .action("REJECT")
        .reasonCode("NICHE_MISMATCH")
        .build();

    RecommendationFeedbackResponse resA = service.processFeedback(campaignId, reqA);
    assertThat(resA.getNewContextVersion()).isEqualTo(2);
    assertThat(resA.getTotalExcludedCount()).isEqualTo(1);

    // Mock cho bước 2: DB hiện có v2 là bản ghi mới nhất
    ObjectNode dataV2 = objectMapper.createObjectNode();
    ArrayNode excludedV2 = objectMapper.createArrayNode().add(kocA.toString());
    dataV2.set("excludedCreatorIds", excludedV2);
    dataV2.set("feedbackHistory", objectMapper.createArrayNode());

    CampaignContext contextV2 = CampaignContext.builder()
        .campaign(campaign)
        .contextVersion(2)
        .contextData(dataV2)
        .active(true)
        .build();

    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(List.of(contextV2, contextV1));

    // Bước 2: Reject KOC B -> tiến hóa lên v3
    RecommendationFeedbackRequest reqB = RecommendationFeedbackRequest.builder()
        .creatorId(kocB)
        .action("REJECT")
        .reasonCode("LOW_ENGAGEMENT")
        .build();

    RecommendationFeedbackResponse resB = service.processFeedback(campaignId, reqB);
    assertThat(resB.getPreviousContextVersion()).isEqualTo(2);
    assertThat(resB.getNewContextVersion()).isEqualTo(3);
    assertThat(resB.getTotalExcludedCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("TEST-10 Idempotence: Rejecting the same creator twice does not duplicate in excludedCreatorIds")
  void testFeedback_idempotentRejection_noDuplicates() {
    UUID creatorId = UUID.randomUUID();

    ObjectNode dataV1 = objectMapper.createObjectNode();
    ArrayNode excluded = objectMapper.createArrayNode();
    excluded.add(creatorId.toString()); // Đã có sẵn trong danh sách
    dataV1.set("excludedCreatorIds", excluded);
    dataV1.set("feedbackHistory", objectMapper.createArrayNode());

    CampaignContext contextV1 = CampaignContext.builder()
        .campaign(campaign)
        .contextVersion(1)
        .contextData(dataV1)
        .active(true)
        .build();

    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(List.of(contextV1));

    RecommendationFeedbackRequest req = RecommendationFeedbackRequest.builder()
        .creatorId(creatorId)
        .action("REJECT")
        .reasonCode("PRICE_TOO_HIGH")
        .build();

    RecommendationFeedbackResponse res = service.processFeedback(campaignId, req);

    assertThat(res.getTotalExcludedCount()).isEqualTo(1); // Vẫn chỉ là 1, không bị duplicate
  }

  @Test
  @DisplayName("Action ACCEPT: Records into feedbackHistory without adding to excludedCreatorIds")
  void testFeedback_acceptAction_recordsHistoryWithoutExclusion() {
    UUID creatorId = UUID.randomUUID();

    ObjectNode dataV1 = objectMapper.createObjectNode();
    dataV1.set("excludedCreatorIds", objectMapper.createArrayNode());
    dataV1.set("feedbackHistory", objectMapper.createArrayNode());

    CampaignContext contextV1 = CampaignContext.builder()
        .campaign(campaign)
        .contextVersion(1)
        .contextData(dataV1)
        .active(true)
        .build();

    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(List.of(contextV1));

    RecommendationFeedbackRequest req = RecommendationFeedbackRequest.builder()
        .creatorId(creatorId)
        .action("ACCEPT")
        .reasonCode("GOOD_FIT")
        .reasonNote("Rất phù hợp với concept hè")
        .build();

    RecommendationFeedbackResponse res = service.processFeedback(campaignId, req);

    assertThat(res.getAction()).isEqualTo("ACCEPT");
    assertThat(res.getTotalExcludedCount()).isEqualTo(0); // Không bị loại trừ

    ArgumentCaptor<CampaignContext> captor = ArgumentCaptor.forClass(CampaignContext.class);
    verify(campaignContextRepository, times(2)).save(captor.capture());

    CampaignContext newCtx = captor.getAllValues().get(1);
    assertThat(newCtx.getContextData().get("excludedCreatorIds")).isEmpty();
    assertThat(newCtx.getContextData().get("feedbackHistory")).hasSize(1);
    assertThat(newCtx.getContextData().get("feedbackHistory").get(0).get("action").asText()).isEqualTo("ACCEPT");
  }
}
