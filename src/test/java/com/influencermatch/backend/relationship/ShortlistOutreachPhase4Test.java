package com.influencermatch.backend.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.creator.dto.InvitationResponse;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorInvitation;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.CreatorInvitationRepository;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.relationship.service.RelationshipServiceImpl;
import com.influencermatch.backend.relationship.service.ShortlistServiceImpl;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class ShortlistOutreachPhase4Test {

  private ShortlistItemRepository shortlistItemRepository;
  private CampaignOwnershipService ownership;
  private CreatorRepository creatorRepository;
  private UserRepository userRepository;
  private RecommendationRunRepository recommendationRunRepository;
  private PublicCreatorMetricRepository publicCreatorMetricRepository;
  private BrandCreatorRelationshipRepository relationshipRepository;
  private CreatorInvitationService creatorInvitationService;
  private CreatorInvitationRepository creatorInvitationRepository;

  private ShortlistServiceImpl shortlistService;
  private RelationshipServiceImpl relationshipService;

  private User brandUser;
  private BrandProfile brandProfile;
  private Campaign campaign;
  private UUID campaignId;

  @BeforeEach
  void setUp() {
    shortlistItemRepository = mock(ShortlistItemRepository.class);
    ownership = mock(CampaignOwnershipService.class);
    creatorRepository = mock(CreatorRepository.class);
    userRepository = mock(UserRepository.class);
    recommendationRunRepository = mock(RecommendationRunRepository.class);
    publicCreatorMetricRepository = mock(PublicCreatorMetricRepository.class);
    relationshipRepository = mock(BrandCreatorRelationshipRepository.class);
    creatorInvitationService = mock(CreatorInvitationService.class);
    creatorInvitationRepository = mock(CreatorInvitationRepository.class);

    shortlistService =
        new ShortlistServiceImpl(
            shortlistItemRepository,
            ownership,
            creatorRepository,
            userRepository,
            recommendationRunRepository,
            publicCreatorMetricRepository,
            relationshipRepository,
            creatorInvitationService,
            creatorInvitationRepository);

    relationshipService =
        new RelationshipServiceImpl(
            relationshipRepository,
            shortlistItemRepository,
            ownership,
            creatorRepository,
            userRepository);

    brandUser = TestActors.user(Role.BRAND);
    brandUser.setEmail("brand@company.com");
    TestActors.authenticate(brandUser);

    brandProfile = BrandProfile.builder().businessName("Brand Corp").user(brandUser).build();
    brandProfile.setId(UUID.randomUUID());

    campaignId = UUID.randomUUID();
    campaign =
        Campaign.builder()
            .name("Autumn Launch 2026")
            .productService("Smart Watch")
            .brandProfile(brandProfile)
            .build();
    campaign.setId(campaignId);

    TestActors.authenticate(brandUser);
    when(ownership.requireOwned(campaignId)).thenReturn(campaign);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName(
      "TEST-F4-09: Brand outreaches to PUBLIC creator with email -> creates CRM relationship and"
          + " enqueues claim invitation")
  void testOutreach_Success_PublicCreatorWithEmail() {
    UUID creatorId = UUID.randomUUID();
    Creator creator =
        Creator.builder()
            .id(creatorId)
            .platform("TIKTOK")
            .userName("tech_reviewer")
            .displayName("Tech Reviewer VN")
            .sourceType(CreatorSourceType.PUBLIC)
            .build();

    UUID itemId = UUID.randomUUID();
    ShortlistItem item =
        ShortlistItem.builder()
            .user(brandUser)
            .id(itemId)
            .campaign(campaign)
            .creator(creator)
            .status(ShortlistStatus.SAVED)
            .priority(ShortlistPriority.HIGH)
            .build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(eq(campaignId), any()))
        .thenReturn(List.of(item));
    when(shortlistItemRepository.save(any(ShortlistItem.class))).thenAnswer(i -> i.getArgument(0));

    // Public metric with contact email
    PublicCreatorMetric metric =
        PublicCreatorMetric.builder()
            .creator(creator)
            .contact("booking@techreviewer.vn")
            .contentSummary("Review công nghệ, smartphone, laptop")
            .build();
    when(publicCreatorMetricRepository.findByCreatorId(creatorId)).thenReturn(Optional.of(metric));

    // No existing relationship
    when(relationshipRepository.findByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(Optional.empty());
    when(relationshipRepository.save(any(BrandCreatorRelationship.class)))
        .thenAnswer(
            i -> {
              BrandCreatorRelationship r = i.getArgument(0);
              if (r.getId() == null) r.setId(UUID.randomUUID());
              return r;
            });

    // No existing pending invitation
    when(creatorInvitationRepository
            .findFirstByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                eq(creatorId), eq("PENDING"), any()))
        .thenReturn(Optional.empty());

    UUID invitationId = UUID.randomUUID();
    when(creatorInvitationService.sendInvitation(
            eq(creatorId), eq("booking@techreviewer.vn"), eq(brandProfile.getId()), eq(campaignId)))
        .thenReturn(
            InvitationResponse.builder()
                .id(invitationId)
                .creatorId(creatorId)
                .email("booking@techreviewer.vn")
                .status("PENDING")
                .emailStatus("QUEUED")
                .build());

    ShortlistOutreachRequest request =
        ShortlistOutreachRequest.builder()
            .shortlistItemIds(List.of(itemId))
            .outreachMessage("Chào bạn, Brand muốn mời bạn test sản phẩm smartwatch mới")
            .proposedFee(BigDecimal.valueOf(15_000_000))
            .currency("VND")
            .contactMethod("EMAIL_INVITATION")
            .build();

    ShortlistOutreachResponse response = shortlistService.outreachShortlist(campaignId, request);

    assertThat(response.getCampaignId()).isEqualTo(campaignId);
    assertThat(response.getContactedCount()).isEqualTo(1);
    assertThat(response.getRelationshipIds()).hasSize(1);
    assertThat(response.getDispatchedInvitationIds()).containsExactly(invitationId);
    assertThat(item.getStatus()).isEqualTo(ShortlistStatus.CONTACTED);

    ShortlistOutreachResponse.OutreachItemResult result = response.getItems().get(0);
    assertThat(result.getContactStatus()).isEqualTo("INVITATION_QUEUED");
    assertThat(result.getInvitationId()).isEqualTo(invitationId);

    verify(creatorInvitationService)
        .sendInvitation(
            eq(creatorId), eq("booking@techreviewer.vn"), eq(brandProfile.getId()), eq(campaignId));
  }

  @Test
  @DisplayName(
      "Outreach to AUTHORIZED creator -> creates CRM relationship and marks PORTAL_NOTIFIED without"
          + " email claim")
  void testOutreach_Success_AuthorizedCreator() {
    UUID creatorId = UUID.randomUUID();
    CreatorMaster master = CreatorMaster.builder().id(UUID.randomUUID()).build();
    Creator creator =
        Creator.builder()
            .id(creatorId)
            .platform("YOUTUBE")
            .userName("dev_channel")
            .displayName("Dev Master")
            .sourceType(CreatorSourceType.AUTHORIZED)
            .master(master)
            .build();

    UUID itemId = UUID.randomUUID();
    ShortlistItem item =
        ShortlistItem.builder()
            .user(brandUser)
            .id(itemId)
            .campaign(campaign)
            .creator(creator)
            .master(master)
            .status(ShortlistStatus.READY_TO_CONTACT)
            .build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(eq(campaignId), any()))
        .thenReturn(List.of(item));
    when(shortlistItemRepository.save(any(ShortlistItem.class))).thenAnswer(i -> i.getArgument(0));

    when(relationshipRepository.findByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(Optional.empty());
    when(relationshipRepository.save(any(BrandCreatorRelationship.class)))
        .thenAnswer(
            i -> {
              BrandCreatorRelationship r = i.getArgument(0);
              if (r.getId() == null) r.setId(UUID.randomUUID());
              return r;
            });

    ShortlistOutreachRequest request =
        ShortlistOutreachRequest.builder()
            .shortlistItemIds(List.of(itemId))
            .outreachMessage("Chào bạn, Brand muốn đề xuất hợp tác trực tiếp")
            .proposedFee(BigDecimal.valueOf(25_000_000))
            .build();

    ShortlistOutreachResponse response = shortlistService.outreachShortlist(campaignId, request);

    assertThat(response.getContactedCount()).isEqualTo(1);
    assertThat(response.getDispatchedInvitationIds()).isEmpty();
    assertThat(response.getItems().get(0).getContactStatus()).isEqualTo("PORTAL_NOTIFIED");
    verify(creatorInvitationService, never()).sendInvitation(any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "Outreach when pending invitation already exists -> links existing invitation without"
          + " duplicate dispatch")
  void testOutreach_PendingInvitationExists() {
    UUID creatorId = UUID.randomUUID();
    Creator creator =
        Creator.builder()
            .id(creatorId)
            .platform("TIKTOK")
            .userName("beauty_girl")
            .displayName("Beauty Girl")
            .sourceType(CreatorSourceType.PUBLIC)
            .build();

    UUID itemId = UUID.randomUUID();
    ShortlistItem item =
        ShortlistItem.builder()
            .user(brandUser)
            .id(itemId)
            .campaign(campaign)
            .creator(creator)
            .status(ShortlistStatus.SAVED)
            .build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(eq(campaignId), any()))
        .thenReturn(List.of(item));
    when(shortlistItemRepository.save(any(ShortlistItem.class))).thenAnswer(i -> i.getArgument(0));

    PublicCreatorMetric metric =
        PublicCreatorMetric.builder().creator(creator).contact("beauty@agency.com").build();
    when(publicCreatorMetricRepository.findByCreatorId(creatorId)).thenReturn(Optional.of(metric));

    UUID existingInvId = UUID.randomUUID();
    CreatorInvitation pendingInv =
        CreatorInvitation.builder()
            .id(existingInvId)
            .status("PENDING")
            .invitedEmail("beauty@agency.com")
            .expiresAt(LocalDateTime.now().plusDays(5))
            .build();
    when(creatorInvitationRepository
            .findFirstByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                eq(creatorId), eq("PENDING"), any()))
        .thenReturn(Optional.of(pendingInv));

    when(relationshipRepository.findByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(Optional.empty());
    when(relationshipRepository.save(any(BrandCreatorRelationship.class)))
        .thenAnswer(
            i -> {
              BrandCreatorRelationship r = i.getArgument(0);
              if (r.getId() == null) r.setId(UUID.randomUUID());
              return r;
            });

    ShortlistOutreachRequest request =
        ShortlistOutreachRequest.builder()
            .shortlistItemIds(List.of(itemId))
            .proposedFee(BigDecimal.valueOf(10_000_000))
            .build();

    ShortlistOutreachResponse response = shortlistService.outreachShortlist(campaignId, request);

    assertThat(response.getDispatchedInvitationIds()).containsExactly(existingInvId);
    assertThat(response.getItems().get(0).getContactStatus())
        .isEqualTo("PENDING_INVITATION_EXISTS");
    verify(creatorInvitationService, never()).sendInvitation(any(), any(), any(), any());
  }

  @Test
  @DisplayName("Outreach creator with social link but no email -> records MANUAL_SOCIAL_LINK")
  void testOutreach_ManualSocialLink() {
    UUID creatorId = UUID.randomUUID();
    Creator creator =
        Creator.builder()
            .id(creatorId)
            .platform("TIKTOK")
            .userName("no_email_koc")
            .profileUrl("https://tiktok.com/@no_email_koc")
            .sourceType(CreatorSourceType.PUBLIC)
            .build();

    UUID itemId = UUID.randomUUID();
    ShortlistItem item =
        ShortlistItem.builder()
            .user(brandUser)
            .id(itemId)
            .campaign(campaign)
            .creator(creator)
            .status(ShortlistStatus.SAVED)
            .build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(eq(campaignId), any()))
        .thenReturn(List.of(item));
    when(shortlistItemRepository.save(any(ShortlistItem.class))).thenAnswer(i -> i.getArgument(0));

    when(publicCreatorMetricRepository.findByCreatorId(creatorId)).thenReturn(Optional.empty());
    when(relationshipRepository.findByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(Optional.empty());
    when(relationshipRepository.save(any(BrandCreatorRelationship.class)))
        .thenAnswer(
            i -> {
              BrandCreatorRelationship r = i.getArgument(0);
              if (r.getId() == null) r.setId(UUID.randomUUID());
              return r;
            });

    ShortlistOutreachRequest request =
        ShortlistOutreachRequest.builder().shortlistItemIds(List.of(itemId)).build();

    ShortlistOutreachResponse response = shortlistService.outreachShortlist(campaignId, request);

    assertThat(response.getContactedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getContactStatus()).isEqualTo("MANUAL_SOCIAL_LINK");
  }

  @Test
  @DisplayName(
      "CRM State Machine: updateStage transitions to COLLABORATING and updates agreedFee and"
          + " nextAction")
  void testRelationship_UpdateStage_WithAgreedFee() {
    UUID relId = UUID.randomUUID();
    UUID creatorId = UUID.randomUUID();
    Creator creator =
        Creator.builder().id(creatorId).displayName("Super Creator").platform("TIKTOK").build();

    BrandCreatorRelationship relationship =
        BrandCreatorRelationship.builder()
            .id(relId)
            .campaign(campaign)
            .creator(creator)
            .user(brandUser)
            .stage(RelationshipStage.CONTACTED)
            .quotedFee(BigDecimal.valueOf(15_000_000))
            .currency("VND")
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    when(relationshipRepository.findByIdAndCampaignIdAndUserId(
            relId, campaignId, brandUser.getId()))
        .thenReturn(Optional.of(relationship));
    when(relationshipRepository.save(any(BrandCreatorRelationship.class)))
        .thenAnswer(i -> i.getArgument(0));

    UpdateRelationshipStageRequest stageRequest =
        UpdateRelationshipStageRequest.builder()
            .stage(RelationshipStage.COLLABORATING)
            .agreedFee(BigDecimal.valueOf(18_000_000))
            .nextAction("Gửi kịch bản mẫu trước ngày 10/11")
            .build();

    RelationshipDto dto = relationshipService.updateStage(campaignId, relId, stageRequest);

    assertThat(dto.getStage()).isEqualTo(RelationshipStage.COLLABORATING);
    assertThat(dto.getAgreedFee()).isEqualByComparingTo(BigDecimal.valueOf(18_000_000));
    assertThat(dto.getNextAction()).isEqualTo("Gửi kịch bản mẫu trước ngày 10/11");
  }
}
