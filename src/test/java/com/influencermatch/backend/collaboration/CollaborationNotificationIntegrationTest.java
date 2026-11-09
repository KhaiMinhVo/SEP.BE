package com.influencermatch.backend.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.collaboration.dto.CreateCollaborationRequest;
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

    service.create(campaignId, req);

    // Verify Notification was dispatched with exact creator user ID as recipient
    verify(notificationService).createAndDispatch(
        eq(creatorUser.getId()),
        eq(NotificationType.COLLABORATION_PROPOSAL),
        eq("Đề xuất hợp tác từ Aura Cosmetics"),
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

    // Verify Notification was dispatched to the brand user ID
    verify(notificationService).createAndDispatch(
        eq(brandUser.getId()),
        eq(NotificationType.EVIDENCE_SUBMITTED),
        eq("Creator đã nộp bài nghiệm thu"),
        contains("Summer Launch 2026"),
        eq("COLLABORATION"),
        eq(collabId));
  }
}

