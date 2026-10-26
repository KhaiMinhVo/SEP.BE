package com.influencermatch.backend.collaboration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.collaboration.dto.*;
import com.influencermatch.backend.collaboration.enums.*;
import com.influencermatch.backend.collaboration.model.*;
import com.influencermatch.backend.collaboration.repository.*;
import com.influencermatch.backend.collaboration.service.CollaborationService;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.repository.CreatorMasterRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;

class CollaborationServiceTest {
  CampaignRepository campaigns = mock(CampaignRepository.class);
  BrandCreatorRelationshipRepository relationships = mock(BrandCreatorRelationshipRepository.class);
  CollaborationRepository collaborations = mock(CollaborationRepository.class);
  CampaignOutcomeRepository outcomes = mock(CampaignOutcomeRepository.class);
  CreatorReviewRepository reviews = mock(CreatorReviewRepository.class);
  CreatorMasterRepository creatorMasters = mock(CreatorMasterRepository.class);
  EvidenceAssetRepository evidenceAssets = mock(EvidenceAssetRepository.class);
  EntityManager em = mock(EntityManager.class);
  jakarta.validation.ValidatorFactory factory;
  CollaborationService service;
  User owner;
  Campaign campaign;
  BrandCreatorRelationship relationship;
  Collaboration collaboration;

  @BeforeEach
  void setup() {
    factory = Validation.buildDefaultValidatorFactory();
    service =
        new CollaborationService(
            campaigns,
            relationships,
            collaborations,
            outcomes,
            reviews,
            creatorMasters,
            evidenceAssets,
            em,
            factory.getValidator());
    owner = User.builder().id(UUID.randomUUID()).role(Role.BRAND).status(UserStatus.ACTIVE).build();
    authenticate(owner);
    campaign =
        Campaign.builder()
            .id(UUID.randomUUID())
            .status(CampaignStatus.ACTIVE)
            .brandProfile(BrandProfile.builder().user(owner).build())
            .build();
    Creator creator = Creator.builder().id(UUID.randomUUID()).build();
    relationship =
        BrandCreatorRelationship.builder()
            .id(UUID.randomUUID())
            .user(owner)
            .campaign(campaign)
            .creator(creator)
            .stage(RelationshipStage.CONTACTED)
            .build();
    collaboration =
        Collaboration.builder()
            .id(UUID.randomUUID())
            .campaign(campaign)
            .creator(creator)
            .relationship(relationship)
            .status(CollaborationStatus.IN_PROGRESS)
            .paymentStatus(CollaborationPaymentStatus.PAID)
            .build();
    when(campaigns.findLockedForCollaborationById(campaign.getId()))
        .thenReturn(Optional.of(campaign));
    when(relationships.findLockedById(relationship.getId())).thenReturn(Optional.of(relationship));
    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(collaborations.findLockedById(collaboration.getId()))
        .thenReturn(Optional.of(collaboration));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    factory.close();
  }

  void authenticate(User user) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
  }

  CreateCollaborationRequest request() {
    return new CreateCollaborationRequest(
        relationship.getId(), new BigDecimal("1200000.50"), " vnd ", " note ");
  }

  RecordOutcomeRequest outcome() {
    return new RecordOutcomeRequest(1000L, 20L, " https://example.com/post ", " result ");
  }

  CreateCreatorReviewRequest review() {
    return new CreateCreatorReviewRequest(5, 4, false, " review ");
  }

  @Test
  void createsAndSynchronizesFeeAndStage() {
    when(collaborations.save(any()))
        .thenAnswer(
            i -> {
              Collaboration c = i.getArgument(0);
              c.setId(UUID.randomUUID());
              return c;
            });
    var response = service.create(campaign.getId(), request());
    assertThat(response.currency()).isEqualTo("VND");
    assertThat(response.status()).isEqualTo(CollaborationStatus.IN_PROGRESS);
    assertThat(response.paymentStatus()).isEqualTo(CollaborationPaymentStatus.PENDING);
    assertThat(response.startAt()).isNotNull();
    assertThat(response.note()).isEqualTo("note");
    assertThat(relationship.getStage()).isEqualTo(RelationshipStage.COLLABORATING);
    assertThat(relationship.getAgreedFee()).isEqualByComparingTo("1200000.50");
    verify(em).refresh(relationship);
  }

  @Test
  void outcomeCompletesOnlyCollaborationAndPreservesPayment() {
    when(outcomes.save(any()))
        .thenAnswer(
            i -> {
              CampaignOutcome o = i.getArgument(0);
              o.setId(UUID.randomUUID());
              return o;
            });
    var response = service.recordOutcome(collaboration.getId(), outcome());
    assertThat(response.views()).isEqualTo(1000L);
    assertThat(response.clicks()).isEqualTo(20L);
    assertThat(response.source()).isEqualTo("SELF_REPORTED");
    assertThat(collaboration.getPublishedContentUrl()).isEqualTo("https://example.com/post");
    assertThat(collaboration.getStatus()).isEqualTo(CollaborationStatus.COMPLETED);
    assertThat(relationship.getStage()).isEqualTo(RelationshipStage.COMPLETED);
    assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
    assertThat(collaboration.getPaymentStatus()).isEqualTo(CollaborationPaymentStatus.PAID);
  }

  @Test
  void absentMetricStaysNullAndZeroIsValid() {
    when(outcomes.save(any()))
        .thenAnswer(
            i -> {
              CampaignOutcome o = i.getArgument(0);
              o.setId(UUID.randomUUID());
              return o;
            });
    var response =
        service.recordOutcome(
            collaboration.getId(),
            new RecordOutcomeRequest(null, 0L, "https://example.com/post", null));
    assertThat(response.views()).isNull();
    assertThat(response.clicks()).isZero();
  }

  @Test
  void reviewUsesAuthenticatedOwnerAndActualOutcome() {
    campaign.setStatus(CampaignStatus.COMPLETED);
    collaboration.setStatus(CollaborationStatus.COMPLETED);
    var stored =
        CampaignOutcome.builder().id(UUID.randomUUID()).collaboration(collaboration).build();
    when(outcomes.findByCollaborationId(collaboration.getId())).thenReturn(Optional.of(stored));
    when(reviews.save(any()))
        .thenAnswer(
            i -> {
              CreatorReview r = i.getArgument(0);
              r.setId(UUID.randomUUID());
              return r;
            });
    var result = service.review(collaboration.getId(), review());
    assertThat(result.reviewedByUserId()).isEqualTo(owner.getId());
    assertThat(result.outcomeId()).isEqualTo(stored.getId());
    assertThat(result.wouldCollaborateAgain()).isFalse();
    assertThat(result.note()).isEqualTo("review");
  }

  @Test
  void duplicateOperationsHaveSpecificConflictCodes() {
    when(collaborations.existsByRelationshipId(relationship.getId())).thenReturn(true);
    assertCode(
        () -> service.create(campaign.getId(), request()), ErrorCode.COLLABORATION_ALREADY_EXISTS);
    when(outcomes.existsByCollaborationId(collaboration.getId())).thenReturn(true);
    assertCode(
        () -> service.recordOutcome(collaboration.getId(), outcome()),
        ErrorCode.OUTCOME_ALREADY_EXISTS);
    collaboration.setStatus(CollaborationStatus.COMPLETED);
    when(outcomes.findByCollaborationId(collaboration.getId()))
        .thenReturn(Optional.of(CampaignOutcome.builder().build()));
    when(reviews.existsByCollaborationId(collaboration.getId())).thenReturn(true);
    assertCode(
        () -> service.review(collaboration.getId(), review()),
        ErrorCode.CREATOR_REVIEW_ALREADY_EXISTS);
  }

  @Test
  void reviewRequiresCompletedCollaborationAndOutcome() {
    assertCode(
        () -> service.review(collaboration.getId(), review()),
        ErrorCode.INVALID_COLLABORATION_STATE);
    collaboration.setStatus(CollaborationStatus.COMPLETED);
    assertCode(
        () -> service.review(collaboration.getId(), review()),
        ErrorCode.INVALID_COLLABORATION_STATE);
  }

  @ParameterizedTest
  @EnumSource(
      value = CampaignStatus.class,
      names = {"DRAFT", "READY_FOR_DISCOVERY", "COMPLETED", "ARCHIVED"})
  void createAndOutcomeRequireActiveCampaign(CampaignStatus status) {
    campaign.setStatus(status);
    assertCode(
        () -> service.create(campaign.getId(), request()), ErrorCode.INVALID_COLLABORATION_STATE);
    assertCode(
        () -> service.recordOutcome(collaboration.getId(), outcome()),
        ErrorCode.INVALID_COLLABORATION_STATE);
  }

  @ParameterizedTest
  @EnumSource(
      value = RelationshipStage.class,
      names = {"SHORTLISTED", "NOT_INTERESTED", "COMPLETED"})
  void createRejectsIneligibleRelationshipStages(RelationshipStage stage) {
    relationship.setStage(stage);
    assertCode(
        () -> service.create(campaign.getId(), request()), ErrorCode.INVALID_COLLABORATION_STATE);
  }

  @Test
  void createFromCollaboratingStageSucceeds() {
    when(collaborations.save(any()))
        .thenAnswer(
            i -> {
              Collaboration c = i.getArgument(0);
              c.setId(UUID.randomUUID());
              return c;
            });
    relationship.setStage(RelationshipStage.COLLABORATING);
    CollaborationResponse response = service.create(campaign.getId(), request());
    assertThat(response.status()).isEqualTo(CollaborationStatus.IN_PROGRESS);
    assertThat(relationship.getStage()).isEqualTo(RelationshipStage.COLLABORATING);
  }

  @Test
  void createWithOptionalFeeAndCurrencyFallsBackToRelationship() {
    when(collaborations.save(any()))
        .thenAnswer(
            i -> {
              Collaboration c = i.getArgument(0);
              c.setId(UUID.randomUUID());
              return c;
            });
    relationship.setStage(RelationshipStage.COLLABORATING);
    relationship.setAgreedFee(new BigDecimal("1800000.00"));
    relationship.setCurrency("VND");
    CreateCollaborationRequest req =
        new CreateCollaborationRequest(relationship.getId(), null, null, "Optional fee note");
    CollaborationResponse response = service.create(campaign.getId(), req);
    assertThat(response.agreedFee()).isEqualByComparingTo("1800000.00");
    assertThat(response.currency()).isEqualTo("VND");
    assertThat(response.status()).isEqualTo(CollaborationStatus.IN_PROGRESS);
  }

  @Test
  void crossCampaignAndInvalidCurrencyDoNotWrite() {
    relationship.setCampaign(Campaign.builder().id(UUID.randomUUID()).build());
    assertCode(() -> service.create(campaign.getId(), request()), ErrorCode.VALIDATION_ERROR);
    relationship.setCampaign(campaign);
    assertCode(
        () ->
            service.create(
                campaign.getId(),
                new CreateCollaborationRequest(relationship.getId(), BigDecimal.ZERO, "ZZZ", null)),
        ErrorCode.VALIDATION_ERROR);
    verify(collaborations, never()).save(any());
  }

  @Test
  void foreignCampaignAndRelationshipOwnerAreForbidden() {
    campaign.getBrandProfile().setUser(User.builder().id(UUID.randomUUID()).build());
    assertCode(() -> service.create(campaign.getId(), request()), ErrorCode.ACCESS_DENIED);
    campaign.getBrandProfile().setUser(owner);
    relationship.setUser(User.builder().id(UUID.randomUUID()).build());
    assertCode(() -> service.create(campaign.getId(), request()), ErrorCode.ACCESS_DENIED);
    verify(collaborations, never()).save(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"ADMIN", "DATA_MANAGER"})
  void serviceGuardRejectsInternalRoles(Role role) {
    owner.setRole(role);
    authenticate(owner);
    assertCode(() -> service.create(campaign.getId(), request()), ErrorCode.ACCESS_DENIED);
    assertCode(
        () -> service.recordOutcome(collaboration.getId(), outcome()), ErrorCode.ACCESS_DENIED);
    assertCode(() -> service.review(collaboration.getId(), review()), ErrorCode.ACCESS_DENIED);
    verifyNoInteractions(campaigns, collaborations, relationships, outcomes, reviews);
  }

  @Test
  void corruptRelationshipCannotBeUsedForOutcome() {
    relationship.setCreator(Creator.builder().id(UUID.randomUUID()).build());
    assertCode(
        () -> service.recordOutcome(collaboration.getId(), outcome()),
        ErrorCode.INVALID_COLLABORATION_STATE);
    verify(outcomes, never()).save(any());
  }

  @Test
  void getCampaignCollaborationsReturnsSummaryPage() {
    when(campaigns.findById(campaign.getId())).thenReturn(Optional.of(campaign));
    when(collaborations.findByCampaignId(eq(campaign.getId()), any()))
        .thenReturn(new PageImpl<>(List.of(collaboration), PageRequest.of(0, 20), 1));
    when(outcomes.existsByCollaborationId(collaboration.getId())).thenReturn(true);
    when(reviews.existsByCollaborationId(collaboration.getId())).thenReturn(false);

    var page = service.getCampaignCollaborations(campaign.getId(), null, PageRequest.of(0, 20));
    assertThat(page.totalElements()).isEqualTo(1);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).id()).isEqualTo(collaboration.getId());
    assertThat(page.items().get(0).hasOutcome()).isTrue();
    assertThat(page.items().get(0).hasReview()).isFalse();
  }

  @Test
  void getCampaignCollaborationsRejectsForeignBrand() {
    campaign.getBrandProfile().setUser(User.builder().id(UUID.randomUUID()).build());
    when(campaigns.findById(campaign.getId())).thenReturn(Optional.of(campaign));
    assertCode(
        () -> service.getCampaignCollaborations(campaign.getId(), null, PageRequest.of(0, 20)),
        ErrorCode.ACCESS_DENIED);
  }

  @Test
  void getCollaborationDetailReturnsFullDetailWithOutcomeAndReview() {
    CampaignOutcome outcome =
        CampaignOutcome.builder()
            .id(UUID.randomUUID())
            .collaboration(collaboration)
            .views(5000L)
            .clicks(150L)
            .resultNote("Great")
            .evidenceQuality("SELF_REPORTED")
            .recordedAt(java.time.LocalDateTime.now())
            .completedAt(java.time.LocalDateTime.now())
            .build();
    CreatorReview review =
        CreatorReview.builder()
            .id(UUID.randomUUID())
            .collaboration(collaboration)
            .outcome(outcome)
            .reviewedBy(owner)
            .overallRating((short) 5)
            .brandFitRating((short) 4)
            .wouldCollaborateAgain(true)
            .note("Pleased")
            .reviewedAt(java.time.LocalDateTime.now())
            .build();

    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(outcomes.findByCollaborationId(collaboration.getId())).thenReturn(Optional.of(outcome));
    when(reviews.findByCollaborationId(collaboration.getId())).thenReturn(Optional.of(review));

    var detail = service.getCollaborationDetail(collaboration.getId());
    assertThat(detail.id()).isEqualTo(collaboration.getId());
    assertThat(detail.outcome()).isNotNull();
    assertThat(detail.outcome().views()).isEqualTo(5000L);
    assertThat(detail.review()).isNotNull();
    assertThat(detail.review().overallRating()).isEqualTo((short) 5);
  }

  @Test
  void getCollaborationDetailRejectsUnrelatedActor() {
    User stranger =
        User.builder().id(UUID.randomUUID()).role(Role.BRAND).status(UserStatus.ACTIVE).build();
    authenticate(stranger);
    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    assertCode(() -> service.getCollaborationDetail(collaboration.getId()), ErrorCode.ACCESS_DENIED);
  }

  @Test
  void getCreatorPortalCollaborationsReturnsListForCreatorMaster() {
    User creatorUser =
        User.builder().id(UUID.randomUUID()).role(Role.CREATOR).status(UserStatus.ACTIVE).build();
    authenticate(creatorUser);
    CreatorMaster master =
        CreatorMaster.builder()
            .id(UUID.randomUUID())
            .user(creatorUser)
            .canonicalName("Master KOC")
            .build();
    when(creatorMasters.findByUserId(creatorUser.getId())).thenReturn(Optional.of(master));
    when(collaborations.findByMasterId(master.getId())).thenReturn(List.of(collaboration));

    var list = service.getCreatorPortalCollaborations(null);
    assertThat(list).hasSize(1);
    assertThat(list.get(0).id()).isEqualTo(collaboration.getId());
  }

  @Test
  void getCreatorPortalCollaborationsReturnsEmptyWhenNoMaster() {
    User creatorUser =
        User.builder().id(UUID.randomUUID()).role(Role.CREATOR).status(UserStatus.ACTIVE).build();
    authenticate(creatorUser);
    when(creatorMasters.findByUserId(creatorUser.getId())).thenReturn(Optional.empty());

    var list = service.getCreatorPortalCollaborations(null);
    assertThat(list).isEmpty();
  }

  @Test
  void submitEvidenceByBrandUpdatesPostUrlAndPersistsAsset() {
    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(evidenceAssets.save(any()))
        .thenAnswer(
            i -> {
              EvidenceAsset a = i.getArgument(0);
              a.setId(UUID.randomUUID());
              return a;
            });

    SubmitEvidenceRequest req =
        new SubmitEvidenceRequest(
            "POST_LINK",
            "https://tiktok.com/@koc/video/999",
            null,
            "Published link");

    EvidenceAssetResponse response = service.submitEvidence(collaboration.getId(), req);
    assertThat(response.assetType()).isEqualTo("POST_LINK");
    assertThat(response.source()).isEqualTo("BRAND_UPLOAD");
    assertThat(response.storageUrl()).isEqualTo("https://tiktok.com/@koc/video/999");
    assertThat(collaboration.getPublishedContentUrl()).isEqualTo("https://tiktok.com/@koc/video/999");
  }

  @Test
  void submitEvidenceByCreatorMasterSetsCreatorSubmittedSource() {
    User creatorUser =
        User.builder().id(UUID.randomUUID()).role(Role.CREATOR).status(UserStatus.ACTIVE).build();
    authenticate(creatorUser);
    CreatorMaster master =
        CreatorMaster.builder().id(UUID.randomUUID()).user(creatorUser).build();
    collaboration.setMaster(master);

    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(evidenceAssets.save(any()))
        .thenAnswer(
            i -> {
              EvidenceAsset a = i.getArgument(0);
              a.setId(UUID.randomUUID());
              return a;
            });

    SubmitEvidenceRequest req =
        new SubmitEvidenceRequest(
            "INSIGHTS_SCREENSHOT",
            "https://s3.amazonaws.com/evidence/insights.png",
            null,
            "Insights proof");

    EvidenceAssetResponse response = service.submitEvidence(collaboration.getId(), req);
    assertThat(response.assetType()).isEqualTo("INSIGHTS_SCREENSHOT");
    assertThat(response.source()).isEqualTo("CREATOR_SUBMITTED");
  }

  @Test
  void submitEvidenceRejectsUnrelatedActor() {
    User stranger =
        User.builder().id(UUID.randomUUID()).role(Role.BRAND).status(UserStatus.ACTIVE).build();
    authenticate(stranger);
    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));

    SubmitEvidenceRequest req =
        new SubmitEvidenceRequest(
            "POST_LINK", "https://example.com/post", null, null);

    assertCode(() -> service.submitEvidence(collaboration.getId(), req), ErrorCode.ACCESS_DENIED);
  }

  @Test
  void getEvidenceAssetsReturnsAllAssetsForCollaboration() {
    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    EvidenceAsset asset =
        EvidenceAsset.builder()
            .id(UUID.randomUUID())
            .collaboration(collaboration)
            .assetType("POST_LINK")
            .storageUrl("https://tiktok.com/@koc/123")
            .source("CREATOR_SUBMITTED")
            .uploadedAt(LocalDateTime.now())
            .build();
    when(evidenceAssets.findByCollaborationIdOrderByUploadedAtDesc(collaboration.getId()))
        .thenReturn(List.of(asset));

    List<EvidenceAssetResponse> list = service.getEvidenceAssets(collaboration.getId());
    assertThat(list).hasSize(1);
    assertThat(list.get(0).assetType()).isEqualTo("POST_LINK");
  }

  void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
