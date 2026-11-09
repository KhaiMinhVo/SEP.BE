package com.influencermatch.backend.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.collaboration.dto.CreateCollaborationRequest;
import com.influencermatch.backend.collaboration.dto.CollaborationResponse;
import com.influencermatch.backend.collaboration.dto.EvidenceAssetResponse;
import com.influencermatch.backend.collaboration.dto.SubmitEvidenceRequest;
import com.influencermatch.backend.collaboration.model.Collaboration;
import com.influencermatch.backend.collaboration.model.EvidenceAsset;
import com.influencermatch.backend.collaboration.repository.CampaignOutcomeRepository;
import com.influencermatch.backend.collaboration.repository.CollaborationRepository;
import com.influencermatch.backend.collaboration.repository.CreatorReviewRepository;
import com.influencermatch.backend.collaboration.repository.EvidenceAssetRepository;
import com.influencermatch.backend.collaboration.service.CollaborationService;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.repository.CreatorMasterRepository;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.notification.enums.NotificationType;
import com.influencermatch.backend.notification.service.NotificationService;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * P0 Integration test: Verifies that real collaboration lifecycle events
 * actually trigger durable notifications with correct recipients and payload.
 */
class CollaborationNotificationIntegrationTest {

  private final CampaignRepository campaigns = mock(CampaignRepository.class);
  private final BrandCreatorRelationshipRepository relationships = mock(BrandCreatorRelationshipRepository.class);
  private final CollaborationRepository collaborations = mock(CollaborationRepository.class);
  private final CampaignOutcomeRepository outcomes = mock(CampaignOutcomeRepository.class);
  private final CreatorReviewRepository reviews = mock(CreatorReviewRepository.class);
  private final CreatorMasterRepository creatorMasters = mock(CreatorMasterRepository.class);
  private final EvidenceAssetRepository evidenceAssets = mock(EvidenceAssetRepository.class);
  private final EntityManager entityManager = mock(EntityManager.class);
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
  private final NotificationService notificationService = mock(NotificationService.class);

  private CollaborationService service;

  private static User user(Role role) {
    return User.builder()
        .id(UUID.randomUUID())
        .email(UUID.randomUUID() + "@unit.test")
        .role(role)
        .status(UserStatus.ACTIVE)
        .build();
  }

  private static void authenticate(User user) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
  }

  @BeforeEach
  void setUp() {
    service =
        new CollaborationService(
            campaigns,
            relationships,
            collaborations,
            outcomes,
            reviews,
            creatorMasters,
            evidenceAssets,
            entityManager,
            validator,
            eventPublisher,
            notificationService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void createCollaborationTriggersNotificationToCreatorOwner() {
    User brandUser = user(Role.BRAND);
    authenticate(brandUser);

    User creatorUser = user(Role.CREATOR);
    CreatorMaster master = CreatorMaster.builder().user(creatorUser).build();
    Creator creator = Creator.builder().master(master).build();

    UUID campaignId = UUID.randomUUID();
    BrandProfile brandProfile = BrandProfile.builder().user(brandUser).businessName("Aura Cosmetics").build();
    Campaign campaign =
        Campaign.builder()
            .id(campaignId)
            .brandProfile(brandProfile)
            .name("Summer Launch 2026")
            .status(CampaignStatus.ACTIVE)
            .build();

    UUID relationshipId = UUID.randomUUID();
    BrandCreatorRelationship relationship =
        BrandCreatorRelationship.builder()
            .id(relationshipId)
            .user(brandUser)
            .campaign(campaign)
            .creator(creator)
            .master(master)
            .stage(RelationshipStage.CONTACTED)
            .build();

    when(campaigns.findLockedForCollaborationById(campaignId)).thenReturn(Optional.of(campaign));
    when(campaigns.findById(campaignId)).thenReturn(Optional.of(campaign));
    when(relationships.findLockedById(relationshipId)).thenReturn(Optional.of(relationship));
    when(collaborations.existsByRelationshipId(relationshipId)).thenReturn(false);
    when(collaborations.save(any(Collaboration.class))).thenAnswer(inv -> {
      Collaboration c = inv.getArgument(0);
      c.setId(UUID.randomUUID());
      return c;
    });

    CreateCollaborationRequest req =
        new CreateCollaborationRequest(relationshipId, BigDecimal.valueOf(5000000), "VND", "Welcome aboard");

    CollaborationResponse response = service.create(campaignId, req);

    assertThat(response.confirmationSource()).isEqualTo("BRAND_RECORDED_EXTERNAL");

    // Verify Notification was dispatched with exact creator user ID as recipient
    verify(notificationService).createAndDispatch(
        eq(creatorUser.getId()),
        eq(NotificationType.COLLABORATION_RECORDED),
        eq("Hợp tác chiến dịch đã được ghi nhận"),
        contains("Summer Launch 2026"),
        eq("COLLABORATION"),
        any(UUID.class));
  }

  @Test
  void submitEvidenceByCreatorTriggersNotificationToBrandOwner() {
    User creatorUser = user(Role.CREATOR);
    authenticate(creatorUser);

    User brandUser = user(Role.BRAND);
    BrandProfile brandProfile = BrandProfile.builder().user(brandUser).businessName("Aura Cosmetics").build();
    Campaign campaign = Campaign.builder().brandProfile(brandProfile).name("Summer Launch 2026").build();

    CreatorMaster master = CreatorMaster.builder().user(creatorUser).build();
    Creator creator = Creator.builder().master(master).build();

    UUID collabId = UUID.randomUUID();
    Collaboration collaboration =
        Collaboration.builder()
            .id(collabId)
            .campaign(campaign)
            .creator(creator)
            .master(master)
            .build();

    when(collaborations.findById(collabId)).thenReturn(Optional.of(collaboration));
    when(evidenceAssets.save(any(EvidenceAsset.class))).thenAnswer(inv -> {
      EvidenceAsset a = inv.getArgument(0);
      a.setId(UUID.randomUUID());
      return a;
    });

    SubmitEvidenceRequest req =
        new SubmitEvidenceRequest(
            "POST_LINK",
            "https://tiktok.com/@koc/video/123",
            "https://tiktok.com/@koc/video/123",
            "Video submitted");

    service.submitEvidence(collabId, req);

    // Verify Notification was dispatched to the brand user ID with EVIDENCE_ASSET identity
    verify(notificationService).createAndDispatch(
        eq(brandUser.getId()),
        eq(NotificationType.EVIDENCE_SUBMITTED),
        eq("Creator đã nộp bài nghiệm thu"),
        contains("Summer Launch 2026"),
        eq("EVIDENCE_ASSET"),
        any(UUID.class));
  }

  @Test
  void whenCreatorHasNoOwner_doesNotDispatchNotification() {
    User brandUser = user(Role.BRAND);
    authenticate(brandUser);

    UUID campaignId = UUID.randomUUID();
    UUID relationshipId = UUID.randomUUID();

    BrandProfile brandProfile = BrandProfile.builder().user(brandUser).businessName("Aura Cosmetics").build();
    Campaign campaign = Campaign.builder().id(campaignId).brandProfile(brandProfile).status(CampaignStatus.ACTIVE).name("Summer Launch 2026").build();

    // Unclaimed creator in CRM: has no owner/user account yet
    Creator creator = Creator.builder().id(UUID.randomUUID()).build();
    BrandCreatorRelationship relationship =
        BrandCreatorRelationship.builder()
            .id(relationshipId)
            .user(brandUser)
            .campaign(campaign)
            .creator(creator)
            .stage(RelationshipStage.CONTACTED)
            .build();

    when(campaigns.findLockedForCollaborationById(campaignId)).thenReturn(Optional.of(campaign));
    when(campaigns.findById(campaignId)).thenReturn(Optional.of(campaign));
    when(relationships.findLockedById(relationshipId)).thenReturn(Optional.of(relationship));
    when(collaborations.existsByRelationshipId(relationshipId)).thenReturn(false);
    when(collaborations.save(any(Collaboration.class))).thenAnswer(inv -> {
      Collaboration c = inv.getArgument(0);
      c.setId(UUID.randomUUID());
      return c;
    });

    CreateCollaborationRequest req =
        new CreateCollaborationRequest(relationshipId, BigDecimal.valueOf(5000000), "VND", "Welcome aboard");

    service.create(campaignId, req);

    // CRITICAL: Unclaimed creator must NEVER trigger phantom notification or unsolicited email
    verify(notificationService, never()).createAndDispatch(any(), any(), any(), any(), any(), any());
  }

  @Test
  void repeatedEvidenceSubmissions_createSeparateNotificationsForDifferentAssets() {
    User creatorUser = user(Role.CREATOR);
    authenticate(creatorUser);

    User brandUser = user(Role.BRAND);
    BrandProfile brandProfile = BrandProfile.builder().user(brandUser).businessName("Aura Cosmetics").build();
    Campaign campaign = Campaign.builder().brandProfile(brandProfile).name("Summer Launch 2026").build();

    CreatorMaster master = CreatorMaster.builder().user(creatorUser).build();
    Creator creator = Creator.builder().master(master).build();

    UUID collabId = UUID.randomUUID();
    Collaboration collaboration =
        Collaboration.builder()
            .id(collabId)
            .campaign(campaign)
            .creator(creator)
            .master(master)
            .build();

    when(collaborations.findById(collabId)).thenReturn(Optional.of(collaboration));

    UUID asset1Id = UUID.randomUUID();
    UUID asset2Id = UUID.randomUUID();

    when(evidenceAssets.save(any(EvidenceAsset.class)))
        .thenAnswer(inv -> {
          EvidenceAsset a = inv.getArgument(0);
          a.setId(asset1Id);
          return a;
        })
        .thenAnswer(inv -> {
          EvidenceAsset a = inv.getArgument(0);
          a.setId(asset2Id);
          return a;
        });

    SubmitEvidenceRequest req1 =
        new SubmitEvidenceRequest("POST_LINK", "https://tiktok.com/@koc/video/1", "https://tiktok.com/@koc/video/1", "Draft 1");
    SubmitEvidenceRequest req2 =
        new SubmitEvidenceRequest("ANALYTICS_IMAGE", "https://storage.cdn/analytics2.png", "https://tiktok.com/@koc/video/1", "Insights screenshot");

    service.submitEvidence(collabId, req1);
    service.submitEvidence(collabId, req2);

    // Verify both evidence submissions on the same collaboration dispatch notifications with their distinct asset IDs
    verify(notificationService).createAndDispatch(
        eq(brandUser.getId()),
        eq(NotificationType.EVIDENCE_SUBMITTED),
        any(),
        any(),
        eq("EVIDENCE_ASSET"),
        eq(asset1Id));

    verify(notificationService).createAndDispatch(
        eq(brandUser.getId()),
        eq(NotificationType.EVIDENCE_SUBMITTED),
        any(),
        any(),
        eq("EVIDENCE_ASSET"),
        eq(asset2Id));

    verify(notificationService, times(2)).createAndDispatch(any(), any(), any(), any(), any(), any());
  }

  @Test
  void whenSubmitEvidenceRetriedWithSameUrl_doesNotCreateDuplicateAssetOrNotification() {
    User creatorUser = user(Role.CREATOR);
    authenticate(creatorUser);

    User brandUser = user(Role.BRAND);
    BrandProfile brandProfile = BrandProfile.builder().user(brandUser).businessName("Aura Cosmetics").build();
    Campaign campaign = Campaign.builder().brandProfile(brandProfile).name("Summer Launch 2026").build();

    CreatorMaster master = CreatorMaster.builder().user(creatorUser).build();
    Creator creator = Creator.builder().master(master).build();

    UUID collabId = UUID.randomUUID();
    Collaboration collaboration =
        Collaboration.builder()
            .id(collabId)
            .campaign(campaign)
            .creator(creator)
            .master(master)
            .build();

    when(collaborations.findById(collabId)).thenReturn(Optional.of(collaboration));

    UUID existingAssetId = UUID.randomUUID();
    EvidenceAsset existingAsset =
        EvidenceAsset.builder()
            .id(existingAssetId)
            .collaboration(collaboration)
            .assetType("POST_LINK")
            .storageUrl("https://tiktok.com/@koc/video/duplicate-test")
            .source("CREATOR_SUBMITTED")
            .build();

    // Mock that asset already exists (e.g. from previous attempt or network retry)
    when(evidenceAssets.findFirstByCollaborationIdAndAssetTypeAndStorageUrl(
            eq(collabId), eq("POST_LINK"), eq("https://tiktok.com/@koc/video/duplicate-test")))
        .thenReturn(Optional.of(existingAsset));

    SubmitEvidenceRequest retryReq =
        new SubmitEvidenceRequest(
            "POST_LINK",
            "https://tiktok.com/@koc/video/duplicate-test",
            "https://tiktok.com/@koc/video/duplicate-test",
            "Retry submit");

    EvidenceAssetResponse response = service.submitEvidence(collabId, retryReq);

    assertThat(response).isNotNull();
    assertThat(response.id()).isEqualTo(existingAssetId);

    // CRITICAL: Asset must NOT be re-saved, and duplicate notification must NOT be dispatched
    verify(evidenceAssets, never()).save(any(EvidenceAsset.class));
    verify(notificationService, never()).createAndDispatch(any(), any(), any(), any(), any(), any());
  }

  @Test
  void whenNoteIsBlankOrNull_rejectsCollaborationCreation() {
    User brandUser = user(Role.BRAND);
    authenticate(brandUser);

    UUID campaignId = UUID.randomUUID();
    UUID relationshipId = UUID.randomUUID();

    BrandProfile brandProfile = BrandProfile.builder().user(brandUser).businessName("Aura Cosmetics").build();
    Campaign campaign = Campaign.builder().id(campaignId).brandProfile(brandProfile).status(CampaignStatus.ACTIVE).name("Summer Launch 2026").build();
    Creator creator = Creator.builder().id(UUID.randomUUID()).build();
    BrandCreatorRelationship relationship =
        BrandCreatorRelationship.builder()
            .id(relationshipId)
            .user(brandUser)
            .campaign(campaign)
            .creator(creator)
            .stage(RelationshipStage.CONTACTED)
            .build();

    when(campaigns.findLockedForCollaborationById(campaignId)).thenReturn(Optional.of(campaign));
    when(campaigns.findById(campaignId)).thenReturn(Optional.of(campaign));
    when(relationships.findLockedById(relationshipId)).thenReturn(Optional.of(relationship));

    // Case 1: Note is null -> Must reject
    CreateCollaborationRequest nullNoteReq =
        new CreateCollaborationRequest(relationshipId, BigDecimal.valueOf(5000000), "VND", null);

    assertThatThrownBy(() -> service.create(campaignId, nullNoteReq))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Collaboration note is required");

    // Case 2: Note is blank -> Must reject
    CreateCollaborationRequest blankNoteReq =
        new CreateCollaborationRequest(relationshipId, BigDecimal.valueOf(5000000), "VND", "   ");

    assertThatThrownBy(() -> service.create(campaignId, blankNoteReq))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Collaboration note is required");
  }

  @Test
  void whenAgreedFeeIsZeroWithValidNote_allowsBarterCollaboration() {
    User creatorUser = user(Role.CREATOR);
    User brandUser = user(Role.BRAND);
    authenticate(brandUser);

    UUID campaignId = UUID.randomUUID();
    UUID relationshipId = UUID.randomUUID();

    BrandProfile brandProfile = BrandProfile.builder().user(brandUser).businessName("Aura Cosmetics").build();
    Campaign campaign = Campaign.builder().id(campaignId).brandProfile(brandProfile).status(CampaignStatus.ACTIVE).name("Summer Launch 2026").build();

    CreatorMaster master = CreatorMaster.builder().user(creatorUser).build();
    Creator creator = Creator.builder().id(UUID.randomUUID()).master(master).build();
    BrandCreatorRelationship relationship =
        BrandCreatorRelationship.builder()
            .id(relationshipId)
            .user(brandUser)
            .campaign(campaign)
            .creator(creator)
            .master(master)
            .stage(RelationshipStage.CONTACTED)
            .build();

    when(campaigns.findLockedForCollaborationById(campaignId)).thenReturn(Optional.of(campaign));
    when(campaigns.findById(campaignId)).thenReturn(Optional.of(campaign));
    when(relationships.findLockedById(relationshipId)).thenReturn(Optional.of(relationship));
    when(collaborations.existsByRelationshipId(relationshipId)).thenReturn(false);
    when(collaborations.save(any(Collaboration.class))).thenAnswer(inv -> {
      Collaboration c = inv.getArgument(0);
      c.setId(UUID.randomUUID());
      return c;
    });

    CreateCollaborationRequest barterReq =
        new CreateCollaborationRequest(
            relationshipId,
            BigDecimal.ZERO,
            "VND",
            "Hợp tác hình thức Barter: Tặng bộ mỹ phẩm trị giá 2.000.000 VND đổi 1 Video Review");

    CollaborationResponse response = service.create(campaignId, barterReq);

    assertThat(response).isNotNull();
    assertThat(response.agreedFee()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.confirmationSource()).isEqualTo("BRAND_RECORDED_EXTERNAL");
    assertThat(response.status()).isEqualTo(com.influencermatch.backend.collaboration.enums.CollaborationStatus.IN_PROGRESS);

    // Verify Notification dispatches with fee 0 VND barter notice
    verify(notificationService).createAndDispatch(
        eq(creatorUser.getId()),
        eq(NotificationType.COLLABORATION_RECORDED),
        eq("Hợp tác chiến dịch đã được ghi nhận"),
        contains("0 VND"),
        eq("COLLABORATION"),
        any(UUID.class));
  }
}

