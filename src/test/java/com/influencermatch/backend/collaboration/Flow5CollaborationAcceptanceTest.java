package com.influencermatch.backend.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandPatternRepository;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.collaboration.controller.CollaborationController;
import com.influencermatch.backend.collaboration.dto.*;
import com.influencermatch.backend.collaboration.enums.CollaborationPaymentStatus;
import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import com.influencermatch.backend.collaboration.event.CollaborationReviewedEvent;
import com.influencermatch.backend.collaboration.listener.CollaborationCompletedEventListener;
import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import com.influencermatch.backend.collaboration.model.Collaboration;
import com.influencermatch.backend.collaboration.model.CreatorReview;
import com.influencermatch.backend.collaboration.model.EvidenceAsset;
import com.influencermatch.backend.collaboration.repository.CampaignOutcomeRepository;
import com.influencermatch.backend.collaboration.repository.CollaborationRepository;
import com.influencermatch.backend.collaboration.repository.CreatorReviewRepository;
import com.influencermatch.backend.collaboration.repository.EvidenceAssetRepository;
import com.influencermatch.backend.collaboration.service.CollaborationService;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.creator.controller.CreatorPortalCollaborationController;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.repository.CreatorMasterRepository;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Acceptance Test Suite toàn diện cho FLOW 5: Campaign Collaboration & Performance Tracking.
 * Bao gồm đầy đủ 10 kịch bản chuẩn mực (TEST-F5-01 -> TEST-F5-10) kiểm chứng chu trình:
 * Khởi tạo Hợp tác -> Đọc dữ liệu Brand/Creator -> Nộp Bằng chứng -> Nghiệm thu KPI -> Đánh giá & Học vào M1/M4 AI.
 */
class Flow5CollaborationAcceptanceTest {

  private CampaignRepository campaigns;
  private BrandCreatorRelationshipRepository relationships;
  private CollaborationRepository collaborations;
  private CampaignOutcomeRepository outcomes;
  private CreatorReviewRepository reviews;
  private CreatorMasterRepository creatorMasters;
  private EvidenceAssetRepository evidenceAssets;
  private HistoricalCampaignRepository historicalCampaigns;
  private BrandPatternRepository brandPatterns;
  private BrandProfileRepository brandProfiles;
  private EntityManager em;
  private ApplicationEventPublisher eventPublisher;
  private ObjectMapper objectMapper;

  private CollaborationService collaborationService;
  private CollaborationCompletedEventListener memorySyncListener;
  private CollaborationController collaborationController;
  private CreatorPortalCollaborationController creatorPortalController;

  private User brandOwner;
  private User creatorUser;
  private BrandProfile brandProfile;
  private Campaign campaign;
  private CreatorMaster creatorMaster;
  private Creator creator;
  private BrandCreatorRelationship relationship;
  private Collaboration collaboration;

  @BeforeEach
  void setUp() {
    campaigns = mock(CampaignRepository.class);
    relationships = mock(BrandCreatorRelationshipRepository.class);
    collaborations = mock(CollaborationRepository.class);
    outcomes = mock(CampaignOutcomeRepository.class);
    reviews = mock(CreatorReviewRepository.class);
    creatorMasters = mock(CreatorMasterRepository.class);
    evidenceAssets = mock(EvidenceAssetRepository.class);
    historicalCampaigns = mock(HistoricalCampaignRepository.class);
    brandPatterns = mock(BrandPatternRepository.class);
    brandProfiles = mock(BrandProfileRepository.class);
    em = mock(EntityManager.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    objectMapper = new ObjectMapper();

    collaborationService =
        new CollaborationService(
            campaigns,
            relationships,
            collaborations,
            outcomes,
            reviews,
            creatorMasters,
            evidenceAssets,
            em,
            Validation.buildDefaultValidatorFactory().getValidator(),
            eventPublisher);

    memorySyncListener =
        new CollaborationCompletedEventListener(
            historicalCampaigns,
            brandPatterns,
            collaborations,
            outcomes,
            brandProfiles,
            objectMapper);

    collaborationController = new CollaborationController(collaborationService);
    creatorPortalController = new CreatorPortalCollaborationController(collaborationService);

    // Chuẩn bị dữ liệu mẫu chuẩn (Fixtures)
    brandOwner =
        User.builder()
            .id(UUID.randomUUID())
            .email("brand@acme.com")
            .role(Role.BRAND)
            .status(UserStatus.ACTIVE)
            .build();

    creatorUser =
        User.builder()
            .id(UUID.randomUUID())
            .email("koc@creator.vn")
            .role(Role.CREATOR)
            .status(UserStatus.ACTIVE)
            .build();

    brandProfile =
        BrandProfile.builder()
            .id(UUID.randomUUID())
            .user(brandOwner)
            .businessName("Acme Cosmetics")
            .build();

    campaign =
        Campaign.builder()
            .id(UUID.randomUUID())
            .brandProfile(brandProfile)
            .name("Summer Glow 2026")
            .status(CampaignStatus.ACTIVE)
            .build();

    creatorMaster =
        CreatorMaster.builder()
            .id(UUID.randomUUID())
            .user(creatorUser)
            .canonicalName("Nguyen Van KOC")
            .build();

    creator =
        Creator.builder()
            .id(UUID.randomUUID())
            .master(creatorMaster)
            .userName("@glow.beauty")
            .displayName("Glow Beauty Review")
            .platform("TIKTOK")
            .avatarUrl("https://cdn.influencermatch.com/avatar.jpg")
            .profileUrl("https://tiktok.com/@glow.beauty")
            .build();

    relationship =
        BrandCreatorRelationship.builder()
            .id(UUID.randomUUID())
            .user(brandOwner)
            .campaign(campaign)
            .creator(creator)
            .master(creatorMaster)
            .stage(RelationshipStage.COLLABORATING)
            .agreedFee(new BigDecimal("3500000.00"))
            .currency("VND")
            .updatedAt(LocalDateTime.now())
            .build();

    collaboration =
        Collaboration.builder()
            .id(UUID.randomUUID())
            .campaign(campaign)
            .relationship(relationship)
            .creator(creator)
            .master(creatorMaster)
            .agreedFee(new BigDecimal("3500000.00"))
            .currency("VND")
            .status(CollaborationStatus.IN_PROGRESS)
            .paymentStatus(CollaborationPaymentStatus.PAID)
            .startAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    when(campaigns.findLockedForCollaborationById(campaign.getId())).thenReturn(Optional.of(campaign));
    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(collaborations.findLockedById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(relationships.findLockedById(relationship.getId())).thenReturn(Optional.of(relationship));

    authenticate(brandOwner);
  }

  private void authenticate(User actor) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
  }

  // =========================================================================
  // TEST-F5-01: Khởi tạo Hợp tác từ Relationship ở Stage COLLABORATING
  // =========================================================================
  @Test
  @DisplayName("TEST-F5-01 [Collab From Collaborating]: Khởi tạo Collaboration thành công từ stage COLLABORATING")
  void testF501_CreateCollaboration_FromCollaboratingStage_Succeeds() {
    when(collaborations.existsByRelationshipId(relationship.getId())).thenReturn(false);
    when(collaborations.save(any(Collaboration.class))).thenAnswer(i -> {
      Collaboration c = i.getArgument(0);
      c.setId(UUID.randomUUID());
      return c;
    });

    // Request không truyền agreedFee -> Tự động fallback về agreedFee từ relationship
    CreateCollaborationRequest req =
        new CreateCollaborationRequest(relationship.getId(), null, null, "Kế hoạch bàn giao 1 video TikTok");

    ResponseEntity<ApiResponse<CollaborationResponse>> response =
        collaborationController.create(campaign.getId(), req);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    CollaborationResponse data = response.getBody().data();
    assertThat(data.status()).isEqualTo(CollaborationStatus.IN_PROGRESS);
    assertThat(data.agreedFee()).isEqualByComparingTo(new BigDecimal("3500000.00"));
    assertThat(data.currency()).isEqualTo("VND");
    assertThat(relationship.getStage()).isEqualTo(RelationshipStage.COLLABORATING);
  }

  // =========================================================================
  // TEST-F5-02: Chặn tạo trùng lặp Collaboration cho cùng Relationship
  // =========================================================================
  @Test
  @DisplayName("TEST-F5-02 [Collab Anti-Duplicate]: Chặn tạo trùng lặp Collaboration cho cùng một Relationship")
  void testF502_CreateCollaboration_DuplicateForSameRelationship_ThrowsConflict() {
    when(campaigns.findById(campaign.getId())).thenReturn(Optional.of(campaign));
    when(relationships.findLockedById(relationship.getId())).thenReturn(Optional.of(relationship));
    when(collaborations.existsByRelationshipId(relationship.getId())).thenReturn(true);

    CreateCollaborationRequest req =
        new CreateCollaborationRequest(relationship.getId(), new BigDecimal("4000000"), "VND", "Thử trùng");

    assertThatThrownBy(() -> collaborationController.create(campaign.getId(), req))
        .isInstanceOf(ConflictException.class)
        .satisfies(ex -> assertThat(((ConflictException) ex).code())
            .isEqualTo(ErrorCode.COLLABORATION_ALREADY_EXISTS));
  }

  // =========================================================================
  // TEST-F5-03: Brand truy vấn danh sách hợp tác trong Campaign
  // =========================================================================
  @Test
  @DisplayName("TEST-F5-03 [Brand Query List]: Brand truy vấn danh sách phân trang các hợp tác trong Campaign")
  void testF503_GetCampaignCollaborations_BrandQueriesCollaborations_ReturnsPaginatedList() {
    when(campaigns.findById(campaign.getId())).thenReturn(Optional.of(campaign));
    when(collaborations.findByCampaignId(eq(campaign.getId()), any()))
        .thenReturn(new PageImpl<>(List.of(collaboration), PageRequest.of(0, 20), 1));
    when(outcomes.findByCollaborationId(collaboration.getId())).thenReturn(Optional.empty());
    when(reviews.existsByCollaborationId(collaboration.getId())).thenReturn(false);

    ResponseEntity<ApiResponse<PageResponse<CollaborationSummaryDto>>> response =
        collaborationController.getCampaignCollaborations(campaign.getId(), null, PageRequest.of(0, 20));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    PageResponse<CollaborationSummaryDto> page = response.getBody().data();
    assertThat(page.totalElements()).isEqualTo(1);
    CollaborationSummaryDto item = page.items().get(0);
    assertThat(item.id()).isEqualTo(collaboration.getId());
    assertThat(item.creatorName()).isEqualTo("Glow Beauty Review");
    assertThat(item.platform()).isEqualTo("TIKTOK");
    assertThat(item.hasOutcome()).isFalse();
    assertThat(item.hasReview()).isFalse();
  }

  // =========================================================================
  // TEST-F5-04: Brand xem chi tiết một hợp tác cụ thể
  // =========================================================================
  @Test
  @DisplayName("TEST-F5-04 [Brand Collab Detail]: Brand xem chi tiết hợp tác gồm outcome, review, evidence assets")
  void testF504_GetCollaborationDetail_BrandQueriesSpecificCollaboration_ReturnsDetailedResponse() {
    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(outcomes.findByCollaborationId(collaboration.getId())).thenReturn(Optional.empty());
    when(reviews.findByCollaborationId(collaboration.getId())).thenReturn(Optional.empty());

    EvidenceAsset asset =
        EvidenceAsset.builder()
            .id(UUID.randomUUID())
            .collaboration(collaboration)
            .assetType("POST_LINK")
            .storageUrl("https://tiktok.com/@glow.beauty/video/1234567")
            .source("CREATOR_SUBMITTED")
            .uploadedAt(LocalDateTime.now())
            .build();
    when(evidenceAssets.findByCollaborationIdOrderByUploadedAtDesc(collaboration.getId()))
        .thenReturn(List.of(asset));

    ResponseEntity<ApiResponse<CollaborationDetailResponse>> response =
        collaborationController.getCollaborationDetail(collaboration.getId());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    CollaborationDetailResponse detail = response.getBody().data();
    assertThat(detail.id()).isEqualTo(collaboration.getId());
    assertThat(detail.campaignName()).isEqualTo("Summer Glow 2026");
    assertThat(detail.brandName()).isEqualTo("Acme Cosmetics");
    assertThat(detail.evidenceAssets()).hasSize(1);
    assertThat(detail.evidenceAssets().get(0).assetType()).isEqualTo("POST_LINK");
  }

  // =========================================================================
  // TEST-F5-05: KOC xem danh sách hợp tác qua Creator Portal
  // =========================================================================
  @Test
  @DisplayName("TEST-F5-05 [Creator Portal View]: KOC xem danh sách công việc hợp tác được giao qua Creator Portal")
  void testF505_CreatorPortalCollaborations_KocQueriesAssignedCollaborations_ReturnsList() {
    authenticate(creatorUser);
    when(creatorMasters.findByUserId(creatorUser.getId())).thenReturn(Optional.of(creatorMaster));
    when(collaborations.findByMasterId(creatorMaster.getId()))
        .thenReturn(List.of(collaboration));
    when(outcomes.findByCollaborationId(collaboration.getId())).thenReturn(Optional.empty());
    when(reviews.existsByCollaborationId(collaboration.getId())).thenReturn(false);

    ApiResponse<List<CreatorCollaborationDto>> response =
        creatorPortalController.getMyCollaborations(null);

    assertThat(response.success()).isTrue();
    List<CreatorCollaborationDto> list = response.data();
    assertThat(list).hasSize(1);
    CreatorCollaborationDto collabItem = list.get(0);
    assertThat(collabItem.id()).isEqualTo(collaboration.getId());
    assertThat(collabItem.campaignName()).isEqualTo("Summer Glow 2026");
    assertThat(collabItem.brandName()).isEqualTo("Acme Cosmetics");
    assertThat(collabItem.status()).isEqualTo(CollaborationStatus.IN_PROGRESS);
  }

  // =========================================================================
  // TEST-F5-06: KOC nộp đường dẫn bài đăng & ảnh Insights khi IN_PROGRESS
  // =========================================================================
  @Test
  @DisplayName("TEST-F5-06 [Submit Evidence]: KOC nộp link bài đăng và ảnh chụp Insights khi IN_PROGRESS")
  void testF506_SubmitEvidence_KocOrBrandSubmitsAsset_UpdatesPostUrlAndPersistsEvidence() {
    authenticate(creatorUser);
    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(evidenceAssets.save(any(EvidenceAsset.class))).thenAnswer(i -> {
      EvidenceAsset a = i.getArgument(0);
      a.setId(UUID.randomUUID());
      return a;
    });

    SubmitEvidenceRequest req =
        new SubmitEvidenceRequest(
            "POST_LINK",
            "https://tiktok.com/@glow.beauty/video/1234567",
            "https://tiktok.com/@glow.beauty/video/1234567",
            "Đã đăng video review chính thức");

    ResponseEntity<ApiResponse<EvidenceAssetResponse>> response =
        collaborationController.submitEvidence(collaboration.getId(), req);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    EvidenceAssetResponse assetDto = response.getBody().data();
    assertThat(assetDto.assetType()).isEqualTo("POST_LINK");
    assertThat(assetDto.source()).isEqualTo("CREATOR_SUBMITTED");
    assertThat(collaboration.getPublishedContentUrl())
        .isEqualTo("https://tiktok.com/@glow.beauty/video/1234567");
  }

  // =========================================================================
  // TEST-F5-07: Brand nghiệm thu hiệu quả tương tác mạng xã hội
  // =========================================================================
  @Test
  @DisplayName("TEST-F5-07 [Record Outcome Metrics]: Brand nghiệm thu Views, Clicks, Likes, chuyển COMPLETED")
  void testF507_RecordOutcome_BrandRecordsMetrics_CompletesCollaborationAndRelationship() {
    authenticate(brandOwner);
    when(collaborations.findLockedById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(campaigns.findById(campaign.getId())).thenReturn(Optional.of(campaign));
    when(relationships.findLockedById(relationship.getId())).thenReturn(Optional.of(relationship));
    when(outcomes.existsByCollaborationId(collaboration.getId())).thenReturn(false);
    when(outcomes.save(any(CampaignOutcome.class))).thenAnswer(i -> {
      CampaignOutcome o = i.getArgument(0);
      o.setId(UUID.randomUUID());
      return o;
    });

    RecordOutcomeRequest req =
        new RecordOutcomeRequest(
            25000L, 450L, 1800L, 120L, 40L, "https://tiktok.com/@glow.beauty/video/1234567", "Tương tác vượt KPI");

    ResponseEntity<ApiResponse<OutcomeResponse>> response =
        collaborationController.outcome(collaboration.getId(), req);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    OutcomeResponse outcomeDto = response.getBody().data();
    assertThat(outcomeDto.views()).isEqualTo(25000L);
    assertThat(outcomeDto.clicks()).isEqualTo(450L);
    assertThat(outcomeDto.likes()).isEqualTo(1800L);
    assertThat(outcomeDto.comments()).isEqualTo(120L);
    assertThat(outcomeDto.shares()).isEqualTo(40L);
    assertThat(outcomeDto.source()).isEqualTo("SELF_REPORTED");

    assertThat(collaboration.getStatus()).isEqualTo(CollaborationStatus.COMPLETED);
    assertThat(relationship.getStage()).isEqualTo(RelationshipStage.COMPLETED);
  }

  // =========================================================================
  // TEST-F5-08: Nghiệm thu thành công phi tài chính (không đòi hỏi ROI)
  // =========================================================================
  @Test
  @DisplayName("TEST-F5-08 [Outcome No Financial ROI]: Nghiệm thu thành công không cần trường doanh thu/chi phí")
  void testF508_RecordOutcome_NonFinancialOutcome_NoFinancialRevenueOrRoiRequired() {
    authenticate(brandOwner);
    when(collaborations.findLockedById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(campaigns.findById(campaign.getId())).thenReturn(Optional.of(campaign));
    when(relationships.findLockedById(relationship.getId())).thenReturn(Optional.of(relationship));
    when(outcomes.existsByCollaborationId(collaboration.getId())).thenReturn(false);

    ArgumentCaptor<CampaignOutcome> outcomeCaptor = ArgumentCaptor.forClass(CampaignOutcome.class);
    when(outcomes.save(outcomeCaptor.capture())).thenAnswer(i -> {
      CampaignOutcome o = i.getArgument(0);
      o.setId(UUID.randomUUID());
      return o;
    });

    RecordOutcomeRequest req =
        new RecordOutcomeRequest(
            5000L, 50L, null, null, null, "https://tiktok.com/@glow.beauty/video/1234567", "Nghiệm thu phi tài chính");

    collaborationController.outcome(collaboration.getId(), req);

    CampaignOutcome persisted = outcomeCaptor.getValue();
    assertThat(persisted.getRevenue()).isNull();
    assertThat(persisted.getRoi()).isNull();
    assertThat(persisted.getActualFee()).isNull();
    assertThat(persisted.getEvidenceQuality()).isEqualTo("SELF_REPORTED");
  }

  // =========================================================================
  // TEST-F5-09: Brand gửi đánh giá Creator Review và phát sinh Event
  // =========================================================================
  @Test
  @DisplayName("TEST-F5-09 [Creator Review]: Brand đánh giá sao, brandFitRating và phát sinh CollaborationReviewedEvent")
  void testF509_CreatorReview_BrandSubmitsReview_SavesReviewAndPublishesEvent() {
    authenticate(brandOwner);
    collaboration.setStatus(CollaborationStatus.COMPLETED);
    CampaignOutcome storedOutcome =
        CampaignOutcome.builder()
            .id(UUID.randomUUID())
            .collaboration(collaboration)
            .views(25000L)
            .clicks(450L)
            .likes(1800L)
            .build();

    when(collaborations.findLockedById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(campaigns.findById(campaign.getId())).thenReturn(Optional.of(campaign));
    when(outcomes.findByCollaborationId(collaboration.getId())).thenReturn(Optional.of(storedOutcome));
    when(reviews.existsByCollaborationId(collaboration.getId())).thenReturn(false);
    when(reviews.save(any(CreatorReview.class))).thenAnswer(i -> {
      CreatorReview r = i.getArgument(0);
      r.setId(UUID.randomUUID());
      return r;
    });

    CreateCreatorReviewRequest req =
        new CreateCreatorReviewRequest(5, 5, true, "KOC làm việc cực kỳ chuyên nghiệp và đúng hạn");

    ResponseEntity<ApiResponse<CreatorReviewResponse>> response =
        collaborationController.review(collaboration.getId(), req);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    CreatorReviewResponse reviewDto = response.getBody().data();
    assertThat(reviewDto.overallRating()).isEqualTo((short) 5);
    assertThat(reviewDto.brandFitRating()).isEqualTo((short) 5);
    assertThat(reviewDto.wouldCollaborateAgain()).isTrue();

    // Xác nhận đã publish domain event
    ArgumentCaptor<CollaborationReviewedEvent> eventCaptor =
        ArgumentCaptor.forClass(CollaborationReviewedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    CollaborationReviewedEvent event = eventCaptor.getValue();
    assertThat(event.collaborationId()).isEqualTo(collaboration.getId());
    assertThat(event.overallRating()).isEqualTo((short) 5);
    assertThat(event.brandFitRating()).isEqualTo((short) 5);
  }

  // =========================================================================
  // TEST-F5-10: Event Listener chuyển hóa kết quả nghiệm thu thành M1 & M4
  // =========================================================================
  @Test
  @DisplayName("TEST-F5-10 [Memory Sync]: Hoàn tất Review tự động sinh bản ghi M1 HistoricalCampaign & cập nhật M4 BrandPattern")
  void testF510_MemorySync_EventListenerProcessesEvent_SyncsM1HistoricalAndM4BrandPattern() {
    collaboration.setStatus(CollaborationStatus.COMPLETED);
    collaboration.setPublishedContentUrl("https://tiktok.com/@glow.beauty/video/1234567");

    CampaignOutcome storedOutcome =
        CampaignOutcome.builder()
            .id(UUID.randomUUID())
            .collaboration(collaboration)
            .views(30000L)
            .clicks(600L)
            .likes(2500L)
            .comments(180L)
            .shares(60L)
            .build();

    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(outcomes.findByCollaborationId(collaboration.getId())).thenReturn(Optional.of(storedOutcome));
    when(brandProfiles.findById(brandProfile.getId())).thenReturn(Optional.of(brandProfile));
    when(brandPatterns.findByBrandProfileIdAndPatternType(brandProfile.getId(), "COLLABORATION_SUCCESS"))
        .thenReturn(Optional.empty());

    CollaborationReviewedEvent event =
        new CollaborationReviewedEvent(
            collaboration.getId(),
            UUID.randomUUID(),
            storedOutcome.getId(),
            campaign.getId(),
            brandProfile.getId(),
            creator.getId(),
            creatorMaster.getId(),
            "TIKTOK",
            (short) 5,
            (short) 5,
            true,
            "Video lên xu hướng, phản hồi rất tích cực");

    // Thực thi Listener
    memorySyncListener.onCollaborationReviewed(event);

    // 1. Kiểm chứng bản ghi Bộ nhớ 1 (M1 HistoricalCampaign)
    ArgumentCaptor<HistoricalCampaign> m1Captor = ArgumentCaptor.forClass(HistoricalCampaign.class);
    verify(historicalCampaigns).save(m1Captor.capture());
    HistoricalCampaign m1 = m1Captor.getValue();
    assertThat(m1.getQuality()).isEqualTo("VERIFIED");
    assertThat(m1.getEvidenceType()).isEqualTo("COLLABORATION_REVIEW");
    assertThat(m1.getCreator().getId()).isEqualTo(creator.getId());
    assertThat(m1.getMaster().getId()).isEqualTo(creatorMaster.getId());
    assertThat(m1.getEvidenceValue().get("reliabilityScore").asDouble()).isEqualTo(1.0);
    assertThat(m1.getEvidenceValue().get("views").asLong()).isEqualTo(30000L);
    assertThat(m1.getEvidenceValue().get("likes").asLong()).isEqualTo(2500L);
    assertThat(m1.getEvidenceValue().get("publishedContentUrl").asText())
        .isEqualTo("https://tiktok.com/@glow.beauty/video/1234567");

    // 2. Kiểm chứng bản ghi Bộ nhớ 4 (M4 BrandPattern)
    ArgumentCaptor<BrandPattern> m4Captor = ArgumentCaptor.forClass(BrandPattern.class);
    verify(brandPatterns).save(m4Captor.capture());
    BrandPattern m4 = m4Captor.getValue();
    assertThat(m4.getPatternType()).isEqualTo("COLLABORATION_SUCCESS");
    assertThat(m4.getEvidenceCount()).isEqualTo(1);
    assertThat(m4.getConfidence()).isEqualByComparingTo(new BigDecimal("0.5500"));
    assertThat(m4.getAverageSuccessfulScore()).isEqualByComparingTo(new BigDecimal("5.0000"));
    assertThat(m4.getPatternValue().get("successCount").asInt()).isEqualTo(1);
  }
}
