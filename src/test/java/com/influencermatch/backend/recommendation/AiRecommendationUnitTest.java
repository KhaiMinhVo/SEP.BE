package com.influencermatch.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.billing.service.SubscriptionService;
import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandPatternRepository;
import com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore;
import com.influencermatch.backend.campaign.enums.CampaignObjective;
import com.influencermatch.backend.campaign.enums.CandidateSource;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.CampaignContext;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.campaign.model.RecommendationRun;
import com.influencermatch.backend.campaign.repository.CampaignContextRepository;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.campaign.service.CampaignService;
import com.influencermatch.backend.collaboration.model.Collaboration;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.creator.repository.PostRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.exception.BadRequestException;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.CampaignEmbeddingPendingException;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.recommendation.dto.ExplainResponseDto;
import com.influencermatch.backend.recommendation.dto.RecommendationResponseDto;
import com.influencermatch.backend.recommendation.dto.ScoreBreakdownDto;
import com.influencermatch.backend.recommendation.engine.FourMemoryTopsisEngine;
import com.influencermatch.backend.recommendation.engine.presentation.enums.DisplayMode;
import com.influencermatch.backend.recommendation.service.AiRecommendationService;
import com.influencermatch.backend.recommendation.service.RecommendationRequestStore;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.support.TestM4Fixtures;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class AiRecommendationUnitTest {

  private SubscriptionService subscriptionService;
  private CampaignOwnershipService ownership;
  private CampaignContextRepository campaignContextRepository;
  private PublicCreatorMetricRepository publicCreatorMetricRepository;
  private HistoricalCampaignRepository historicalCampaignRepository;
  private BrandPatternRepository brandPatternRepository;
  private ShortlistItemRepository shortlistItemRepository;
  private RecommendationRunRepository recommendationRunRepository;
  private AiServiceClient aiServiceClient;
  private CampaignService campaignService;
  private RecommendationRequestStore recommendationRequests;
  private PostRepository postRepository;
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
    campaignService = mock(CampaignService.class);
    recommendationRequests = mock(RecommendationRequestStore.class);
    postRepository = mock(PostRepository.class);

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
        new ObjectMapper().findAndRegisterModules());
    TestM4Fixtures.wire(service);
    service.setPostRepository(postRepository);
    ReflectionTestUtils.setField(service, "campaignService", campaignService);
    ReflectionTestUtils.setField(service, "recommendationRequests", recommendationRequests);

    brandUser = TestActors.user(Role.BRAND);
    TestActors.authenticate(brandUser);

    campaignId = UUID.randomUUID();
    brandProfileId = UUID.randomUUID();
    subscriptionId = UUID.randomUUID();
    brandProfile = BrandProfile.builder().id(brandProfileId).user(brandUser).build();
    campaign = Campaign.builder()
        .id(campaignId)
        .brandProfile(brandProfile)
        .name("Summer Beauty")
        .productService("Organic Lip Balm")
        .objective(CampaignObjective.AWARENESS)
        .niches(List.of("Beauty"))
        .platforms(List.of("TIKTOK"))
        .followerMin(10_000L)
        .followerMax(500_000L)
        .build();

    when(ownership.requireOwned(campaignId)).thenReturn(campaign);
    when(shortlistItemRepository.countByCampaignIdAndStatusNot(eq(campaignId), any())).thenReturn(1L);
    when(subscriptionService.reserveRecommendationQuota(brandProfileId)).thenReturn(subscriptionId);
    when(recommendationRunRepository.save(any(RecommendationRun.class)))
        .thenAnswer(inv -> {
          RecommendationRun run = inv.getArgument(0);
          run.setId(UUID.randomUUID());
          return run;
        });
    when(aiServiceClient.getExplanation(any()))
        .thenReturn(ExplainResponseDto.builder()
            .status("success")
            .summary("High match")
            .reasons(List.of("Relevant niche"))
            .explanationAvailable(true)
            .build());
    when(postRepository.findByCreatorIdIn(any()))
        .thenAnswer(inv -> {
          Collection<UUID> ids = inv.getArgument(0);
          List<Post> posts = new ArrayList<>();
          for (UUID id : ids) {
            Creator c = Creator.builder().id(id).build();
            for (int i = 1; i <= 3; i++) {
              posts.add(Post.builder()
                  .id(UUID.randomUUID())
                  .creator(c)
                  .views(10_000L * i)
                  .likes(500L * i)
                  .comments(50L * i)
                  .shares(10L * i)
                  .postedAt("2026-10-0" + i + "T10:00:00Z")
                  .build());
            }
          }
          return posts;
        });
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  // ---- calculateFourMemoryScores (5 UTCIDs) ----

  @Test
  @DisplayName("calculateFourMemoryScores UTCID01 [N]: Creator history and brand patterns passed to engine")
  void calculateFourMemoryScores_withHistoryAndBrandId() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, null, 0.85);
    Campaign otherCampaign = Campaign.builder().id(UUID.randomUUID()).build();
    HistoricalCampaign hc = HistoricalCampaign.builder()
        .id(UUID.randomUUID())
        .collaboration(Collaboration.builder().campaign(otherCampaign).agreedFee(BigDecimal.valueOf(5_000_000)).build())
        .recordedAt(LocalDateTime.now().minusDays(30))
        .build();
    BrandPattern pattern = BrandPattern.builder().patternType("NICHE").confidence(BigDecimal.valueOf(0.8)).build();

    when(historicalCampaignRepository.findByCreatorId(creatorId)).thenReturn(List.of(hc));
    when(brandPatternRepository.findByBrandProfileId(brandProfileId)).thenReturn(List.of(pattern));

    ScoreBreakdownDto breakdown = service.calculateFourMemoryScores(cand, campaign, brandProfileId);

    assertThat(breakdown).isNotNull();
    verify(historicalCampaignRepository).findByCreatorId(creatorId);
    verify(historicalCampaignRepository, never()).findByMasterId(any());
    verify(brandPatternRepository).findByBrandProfileId(brandProfileId);
  }

  @Test
  @DisplayName("calculateFourMemoryScores UTCID02 [B]: Zero creator history falls back to master history")
  void calculateFourMemoryScores_zeroCreatorHistoryQueriesMasterId() {
    UUID creatorId = UUID.randomUUID();
    UUID masterId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, masterId, 0.75);
    HistoricalCampaign masterHc = HistoricalCampaign.builder()
        .id(UUID.randomUUID())
        .recordedAt(LocalDateTime.now().minusDays(20))
        .build();

    when(historicalCampaignRepository.findByCreatorId(creatorId)).thenReturn(List.of());
    when(historicalCampaignRepository.findByMasterId(masterId)).thenReturn(List.of(masterHc));
    when(brandPatternRepository.findByBrandProfileId(brandProfileId)).thenReturn(List.of());

    ScoreBreakdownDto breakdown = service.calculateFourMemoryScores(cand, campaign, brandProfileId);

    assertThat(breakdown).isNotNull();
    verify(historicalCampaignRepository).findByCreatorId(creatorId);
    verify(historicalCampaignRepository).findByMasterId(masterId);
  }

  @Test
  @DisplayName("calculateFourMemoryScores UTCID03 [B]: No history with null masterId and null brandProfileId")
  void calculateFourMemoryScores_noHistoryNullMasterAndBrand() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, null, 0.65);
    when(historicalCampaignRepository.findByCreatorId(creatorId)).thenReturn(List.of());

    ScoreBreakdownDto breakdown = service.calculateFourMemoryScores(cand, campaign, null);

    assertThat(breakdown).isNotNull();
    verify(historicalCampaignRepository, never()).findByMasterId(any());
    verify(brandPatternRepository, never()).findByBrandProfileId(any());
  }

  @Test
  @DisplayName("calculateFourMemoryScores UTCID04 [N]: History from current campaign is excluded before evaluation")
  void calculateFourMemoryScores_excludesCurrentCampaignHistory() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, null, 0.8);
    HistoricalCampaign currentHc = HistoricalCampaign.builder()
        .id(UUID.randomUUID())
        .collaboration(Collaboration.builder().campaign(campaign).build())
        .recordedAt(LocalDateTime.now().minusDays(5))
        .build();
    HistoricalCampaign pastHc = HistoricalCampaign.builder()
        .id(UUID.randomUUID())
        .collaboration(Collaboration.builder().campaign(Campaign.builder().id(UUID.randomUUID()).build()).build())
        .recordedAt(LocalDateTime.now().minusDays(60))
        .build();

    FourMemoryTopsisEngine spyEngine = spy(new FourMemoryTopsisEngine());
    ReflectionTestUtils.setField(service, "fourMemoryTopsisEngine", spyEngine);
    when(historicalCampaignRepository.findByCreatorId(creatorId)).thenReturn(List.of(currentHc, pastHc));

    service.calculateFourMemoryScores(cand, campaign, null);

    @SuppressWarnings("unchecked")
    var historyCaptor = (org.mockito.ArgumentCaptor<List<HistoricalCampaign>>)
        (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(List.class);
    verify(spyEngine).evaluateSingle(eq(cand), isNull(), eq(campaign), historyCaptor.capture(), eq(List.of()));
    assertThat(historyCaptor.getValue()).containsExactly(pastHc);
  }

  @Test
  @DisplayName("calculateFourMemoryScores UTCID05 [A]: Propagates IllegalStateException from engine")
  void calculateFourMemoryScores_engineThrowsIllegalState_propagated() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, null, 0.8);
    FourMemoryTopsisEngine mockEngine = mock(FourMemoryTopsisEngine.class);
    ReflectionTestUtils.setField(service, "fourMemoryTopsisEngine", mockEngine);
    when(historicalCampaignRepository.findByCreatorId(creatorId)).thenReturn(List.of());
    when(mockEngine.evaluateSingle(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("TOPSIS_SCORE_CORRUPTED"));

    assertThatThrownBy(() -> service.calculateFourMemoryScores(cand, campaign, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TOPSIS_SCORE_CORRUPTED");
    verify(mockEngine, never()).toScoreBreakdownDto(any());
  }

  // ---- recommendKocsWithAi (UTCID02..05; UTCID01 in Phase2Test) ----

  @Test
  @DisplayName("recommendKocsWithAi UTCID02 [B]: Single eligible shortlist candidate proceeds and reserves quota once")
  void recommendKocs_singleEligibleCandidateReservesQuotaOnce() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, null, 0.9);
    when(publicCreatorMetricRepository.findCandidatesByShortlist(any(), anyString(), anyBoolean(), any()))
        .thenReturn(List.of(cand));

    RecommendationResponseDto res = service.recommendKocs(campaignId, 10);

    assertThat(res.getRecommendations()).hasSize(1);
    assertThat(res.getRecommendations().getFirst().getCreatorId()).isEqualTo(creatorId);
    verify(subscriptionService, times(1)).reserveRecommendationQuota(brandProfileId);
  }

  @Test
  @DisplayName("recommendKocsWithAi UTCID03 [A]: No eligible candidates throws RECOMMENDATION_NO_CANDIDATES without reserving quota")
  void recommendKocs_noCandidatesThrowsWithoutQuotaReservation() {
    when(publicCreatorMetricRepository.findCandidatesByShortlist(any(), anyString(), anyBoolean(), any()))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service.recommendKocs(campaignId, 10))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.RECOMMENDATION_NO_CANDIDATES));
    verify(subscriptionService, never()).reserveRecommendationQuota(any());
  }

  @Test
  @DisplayName("recommendKocsWithAi UTCID04 [A]: Quota reservation failure propagates QUOTA_EXCEEDED")
  void recommendKocs_quotaExceededPropagates() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), null, 0.85);
    when(publicCreatorMetricRepository.findCandidatesByShortlist(any(), anyString(), anyBoolean(), any()))
        .thenReturn(List.of(cand));
    when(subscriptionService.reserveRecommendationQuota(brandProfileId))
        .thenThrow(new ConflictException(ErrorCode.QUOTA_EXCEEDED, "AI quota exceeded"));

    assertThatThrownBy(() -> service.recommendKocs(campaignId, 10))
        .isInstanceOfSatisfying(ConflictException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED));
    verify(recommendationRunRepository, never()).save(any());
  }

  @Test
  @DisplayName("recommendKocsWithAi UTCID05 [A]: Unready campaign embedding throws CampaignEmbeddingPendingException without reserving quota")
  void recommendKocs_unreadyEmbeddingThrowsWithoutQuota() {
    CampaignEmbeddingStore store = mock(CampaignEmbeddingStore.class);
    when(store.ready(any())).thenReturn(null);
    when(store.view(any())).thenReturn(new CampaignEmbeddingStore.View("PENDING", "model", "v1", null));
    ReflectionTestUtils.setField(service, "m3Embeddings", store);

    assertThatThrownBy(() -> service.recommendKocs(campaignId, 10))
        .isInstanceOf(CampaignEmbeddingPendingException.class);
    verify(subscriptionService, never()).reserveRecommendationQuota(any());
  }

  // ---- discoverCreatorsWithAi sheet -> AiRecommendationService.recommendKocs with idempotencyKey (5 UTCIDs) ----

  @Test
  @DisplayName("recommendKocs (discoverCreatorsWithAi sheet UTCID01) [N]: Valid idempotency key returns cached response")
  void recommendKocsWithIdempotency_returnsCachedResponse() {
    RecommendationResponseDto cached = RecommendationResponseDto.builder()
        .campaignId(campaignId).totalCandidates(1).build();
    when(recommendationRequests.replay(eq(campaignId), eq("key-123"), anyString())).thenReturn(cached);

    RecommendationResponseDto res = service.recommendKocs(
        campaignId, 10, CandidateSource.SHORTLIST, DisplayMode.BEST_MATCH, null, "key-123", "\"campaign:r0\"");

    assertThat(res).isSameAs(cached);
    verify(subscriptionService, never()).reserveRecommendationQuota(any());
  }

  @Test
  @DisplayName("recommendKocs (discoverCreatorsWithAi sheet UTCID02) [B]: 100-character idempotency key accepted")
  void recommendKocsWithIdempotency_100CharKeyAccepted() {
    String key100 = "a".repeat(100);
    RecommendationResponseDto cached = RecommendationResponseDto.builder()
        .campaignId(campaignId).totalCandidates(0).build();
    when(recommendationRequests.replay(eq(campaignId), eq(key100), anyString())).thenReturn(cached);

    RecommendationResponseDto res = service.recommendKocs(
        campaignId, 10, CandidateSource.SHORTLIST, DisplayMode.BEST_MATCH, null, key100, "\"campaign:r0\"");

    assertThat(res).isSameAs(cached);
  }

  @Test
  @DisplayName("recommendKocs (discoverCreatorsWithAi sheet UTCID03) [A]: 101-character idempotency key throws ValidationException")
  void recommendKocsWithIdempotency_101CharKeyThrowsValidation() {
    String key101 = "a".repeat(101);

    assertThatThrownBy(() -> service.recommendKocs(
        campaignId, 10, CandidateSource.SHORTLIST, DisplayMode.BEST_MATCH, null, key101, "\"campaign:r0\""))
        .isInstanceOf(ValidationException.class);
    verifyNoInteractions(recommendationRequests);
  }

  @Test
  @DisplayName("recommendKocs (discoverCreatorsWithAi sheet UTCID04) [A]: No cached response and unavailable embedding throws CampaignEmbeddingPendingException")
  void recommendKocsWithIdempotency_embeddingUnavailableThrows() {
    CampaignEmbeddingStore store = mock(CampaignEmbeddingStore.class);
    when(store.ready(any())).thenReturn(null);
    when(store.view(any())).thenReturn(new CampaignEmbeddingStore.View("PENDING", "model", "v1", null));
    ReflectionTestUtils.setField(service, "m3Embeddings", store);
    when(campaignContextRepository.findByCampaignIdOrderByContextVersionDesc(campaignId))
        .thenReturn(List.of(CampaignContext.builder().id(UUID.randomUUID()).embeddingSourceContextId(UUID.randomUUID()).build()));

    assertThatThrownBy(() -> service.recommendKocs(
        campaignId, 10, CandidateSource.SHORTLIST, DisplayMode.BEST_MATCH, null, "valid-key", "\"campaign:r0\""))
        .isInstanceOf(CampaignEmbeddingPendingException.class);
  }

  @Test
  @DisplayName("recommendKocs (discoverCreatorsWithAi sheet UTCID05) [A]: CandidateSource DISCOVERY throws BadRequestException")
  void recommendKocsWithIdempotency_discoverySourceRejected() {
    assertThatThrownBy(() -> service.recommendKocs(
        campaignId, 10, CandidateSource.DISCOVERY, DisplayMode.BEST_MATCH, null, "valid-key", "\"campaign:r0\""))
        .isInstanceOf(BadRequestException.class);
    verify(subscriptionService, never()).reserveRecommendationQuota(any());
  }

  private RecommendationCandidateProjection mockCandidate(UUID creatorId, UUID masterId, double semantic) {
    RecommendationCandidateProjection cand = mock(RecommendationCandidateProjection.class);
    when(cand.getCreatorId()).thenReturn(creatorId);
    when(cand.getMasterId()).thenReturn(masterId);
    when(cand.getPlatform()).thenReturn("TIKTOK");
    when(cand.getAccountStatus()).thenReturn("ACTIVE");
    when(cand.getDisplayName()).thenReturn("Creator " + creatorId.toString().substring(0, 4));
    when(cand.getUserName()).thenReturn("user_" + creatorId.toString().substring(0, 4));
    when(cand.getFollowers()).thenReturn(50_000L);
    when(cand.getAvgViews()).thenReturn(BigDecimal.valueOf(15_000));
    when(cand.getAvgLikes()).thenReturn(BigDecimal.valueOf(750));
    when(cand.getEngagementRate()).thenReturn(BigDecimal.valueOf(0.05));
    when(cand.getNiche()).thenReturn("Beauty");
    when(cand.getCategory()).thenReturn("Skincare");
    when(cand.getCreatorType()).thenReturn("KOC");
    when(cand.getSemanticScore()).thenReturn(semantic);
    return cand;
  }
}
