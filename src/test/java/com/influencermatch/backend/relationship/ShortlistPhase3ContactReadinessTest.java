package com.influencermatch.backend.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.relationship.service.ShortlistServiceImpl;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class ShortlistPhase3ContactReadinessTest {

  private ShortlistItemRepository shortlistItemRepository;
  private CampaignOwnershipService ownership;
  private CreatorRepository creatorRepository;
  private UserRepository userRepository;
  private RecommendationRunRepository recommendationRunRepository;
  private PublicCreatorMetricRepository publicCreatorMetricRepository;
  private BrandCreatorRelationshipRepository relationshipRepository;

  private ShortlistServiceImpl service;

  private User brandUser;
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

    service = new ShortlistServiceImpl(
        shortlistItemRepository,
        ownership,
        creatorRepository,
        userRepository,
        recommendationRunRepository,
        publicCreatorMetricRepository,
        relationshipRepository
    );

    brandUser = TestActors.user(Role.BRAND);
    TestActors.authenticate(brandUser);

    campaignId = UUID.randomUUID();
    campaign = Campaign.builder().id(campaignId).build();
    when(ownership.requireOwned(campaignId)).thenReturn(campaign);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("SL-02: updateShortlistItem updates priority, note, and transitions status SAVED -> READY_TO_CONTACT")
  void testUpdateShortlistItem_success() {
    UUID itemId = UUID.randomUUID();
    Creator creator = Creator.builder().id(UUID.randomUUID()).displayName("Thao Nhi").build();
    ShortlistItem item = ShortlistItem.builder()
        .id(itemId)
        .campaign(campaign)
        .creator(creator)
        .user(brandUser)
        .priority(ShortlistPriority.LOW)
        .status(ShortlistStatus.SAVED)
        .addedAt(LocalDateTime.now())
        .build();

    when(shortlistItemRepository.findByIdAndCampaignId(itemId, campaignId)).thenReturn(Optional.of(item));
    when(shortlistItemRepository.save(any(ShortlistItem.class))).thenAnswer(inv -> inv.getArgument(0));

    UpdateShortlistItemRequest req = UpdateShortlistItemRequest.builder()
        .priority(ShortlistPriority.HIGH)
        .note("Ưu tiên liên hệ trước")
        .status(ShortlistStatus.READY_TO_CONTACT)
        .build();

    ShortlistItemDto updated = service.updateShortlistItem(campaignId, itemId, req);

    assertThat(updated.getPriority()).isEqualTo(ShortlistPriority.HIGH);
    assertThat(updated.getStatus()).isEqualTo(ShortlistStatus.READY_TO_CONTACT);
    assertThat(updated.getNote()).isEqualTo("Ưu tiên liên hệ trước");
    verify(shortlistItemRepository).save(item);
  }

  @Test
  @DisplayName("SL-02: batchUpdateStatus updates status for multiple shortlist items in bulk")
  void testBatchUpdateStatus_bulkMarkReady() {
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    Creator c1 = Creator.builder().id(UUID.randomUUID()).displayName("KOC 1").build();
    Creator c2 = Creator.builder().id(UUID.randomUUID()).displayName("KOC 2").build();

    ShortlistItem item1 = ShortlistItem.builder().id(id1).campaign(campaign).creator(c1).status(ShortlistStatus.SAVED).addedAt(LocalDateTime.now()).build();
    ShortlistItem item2 = ShortlistItem.builder().id(id2).campaign(campaign).creator(c2).status(ShortlistStatus.SAVED).addedAt(LocalDateTime.now()).build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, List.of(id1, id2)))
        .thenReturn(List.of(item1, item2));
    when(shortlistItemRepository.save(any(ShortlistItem.class))).thenAnswer(inv -> inv.getArgument(0));

    BatchUpdateStatusRequest req = BatchUpdateStatusRequest.builder()
        .itemIds(List.of(id1, id2))
        .status(ShortlistStatus.READY_TO_CONTACT)
        .build();

    List<ShortlistItemDto> results = service.batchUpdateStatus(campaignId, req);

    assertThat(results).hasSize(2);
    assertThat(results).allMatch(r -> r.getStatus() == ShortlistStatus.READY_TO_CONTACT);
    verify(shortlistItemRepository, times(2)).save(any(ShortlistItem.class));
  }

  @Test
  @DisplayName("TEST-09 & SL-04: Case 2 - Chưa Claim nhưng có booking email -> READY qua SYSTEM_EMAIL_OUTBOX")
  void testContactReadiness_unclaimedWithBookingEmail() {
    UUID creatorId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();

    Creator creator = Creator.builder()
        .id(creatorId)
        .displayName("Mai Anh")
        .platform("TIKTOK")
        .build();

    ShortlistItem item = ShortlistItem.builder()
        .id(itemId)
        .campaign(campaign)
        .creator(creator)
        .status(ShortlistStatus.READY_TO_CONTACT)
        .addedAt(LocalDateTime.now())
        .build();

    PublicCreatorMetric metric = PublicCreatorMetric.builder()
        .creator(creator)
        .contact("Booking công việc qua mail: maianh.work@gmail.com - phone 0901234567")
        .build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, List.of(itemId)))
        .thenReturn(List.of(item));
    when(relationshipRepository.existsByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(false);
    when(publicCreatorMetricRepository.findByCreatorId(creatorId))
        .thenReturn(Optional.of(metric));

    ContactReadinessResponse response = service.checkContactReadiness(
        campaignId, ContactReadinessRequest.builder().itemIds(List.of(itemId)).build());

    assertThat(response.getReadyCount()).isEqualTo(1);
    assertThat(response.getItems()).hasSize(1);
    var itemRes = response.getItems().get(0);
    assertThat(itemRes.getReadinessStatus()).isEqualTo("READY");
    assertThat(itemRes.getContactChannel()).isEqualTo("SYSTEM_EMAIL_OUTBOX");
    assertThat(itemRes.getContactTarget()).isEqualTo("maianh.work@gmail.com");
    assertThat(itemRes.isCanProceed()).isTrue();
  }

  @Test
  @DisplayName("SL-04: Case 1 - KOC đã Claim kênh -> READY qua IN_APP_PORTAL")
  void testContactReadiness_claimedCreator() {
    UUID creatorId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();

    User claimedUser = User.builder().id(UUID.randomUUID()).email("creator.portal@gmail.com").build();
    CreatorMaster master = CreatorMaster.builder().id(UUID.randomUUID()).user(claimedUser).build();

    Creator creator = Creator.builder()
        .id(creatorId)
        .master(master)
        .displayName("Trang Dao Verified")
        .platform("TIKTOK")
        .build();

    ShortlistItem item = ShortlistItem.builder()
        .id(itemId)
        .campaign(campaign)
        .creator(creator)
        .status(ShortlistStatus.READY_TO_CONTACT)
        .addedAt(LocalDateTime.now())
        .build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, List.of(itemId)))
        .thenReturn(List.of(item));
    when(relationshipRepository.existsByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(false);

    ContactReadinessResponse response = service.checkContactReadiness(
        campaignId, ContactReadinessRequest.builder().itemIds(List.of(itemId)).build());

    assertThat(response.getReadyCount()).isEqualTo(1);
    var itemRes = response.getItems().get(0);
    assertThat(itemRes.getReadinessStatus()).isEqualTo("READY");
    assertThat(itemRes.getContactChannel()).isEqualTo("IN_APP_PORTAL");
    assertThat(itemRes.getContactTarget()).isEqualTo("creator.portal@gmail.com");
    assertThat(itemRes.isCanProceed()).isTrue();
  }

  @Test
  @DisplayName("SL-04: Case 5 - KOC đã có quan hệ CRM đang hoạt động -> ALREADY_CONTACTED chặn gửi trùng")
  void testContactReadiness_alreadyContacted() {
    UUID creatorId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();

    Creator creator = Creator.builder()
        .id(creatorId)
        .displayName("KOC In CRM")
        .platform("TIKTOK")
        .build();

    ShortlistItem item = ShortlistItem.builder()
        .id(itemId)
        .campaign(campaign)
        .creator(creator)
        .status(ShortlistStatus.READY_TO_CONTACT)
        .addedAt(LocalDateTime.now())
        .build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, List.of(itemId)))
        .thenReturn(List.of(item));
    when(relationshipRepository.existsByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(true);

    ContactReadinessResponse response = service.checkContactReadiness(
        campaignId, ContactReadinessRequest.builder().itemIds(List.of(itemId)).build());

    assertThat(response.getAlreadyContactedCount()).isEqualTo(1);
    var itemRes = response.getItems().get(0);
    assertThat(itemRes.getReadinessStatus()).isEqualTo("ALREADY_CONTACTED");
    assertThat(itemRes.getContactChannel()).isEqualTo("EXISTING_INVITATION");
    assertThat(itemRes.isCanProceed()).isFalse();
  }

  @Test
  @DisplayName("SL-04: Case 3 - Chưa Claim, chỉ có Profile URL -> MANUAL_CONTACT")
  void testContactReadiness_manualSocialLink() {
    UUID creatorId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();

    Creator creator = Creator.builder()
        .id(creatorId)
        .displayName("KOC Social Only")
        .platform("TIKTOK")
        .profileUrl("https://tiktok.com/@koc_social")
        .build();

    ShortlistItem item = ShortlistItem.builder()
        .id(itemId)
        .campaign(campaign)
        .creator(creator)
        .status(ShortlistStatus.READY_TO_CONTACT)
        .addedAt(LocalDateTime.now())
        .build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, List.of(itemId)))
        .thenReturn(List.of(item));
    when(relationshipRepository.existsByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(false);
    when(publicCreatorMetricRepository.findByCreatorId(creatorId))
        .thenReturn(Optional.empty());

    ContactReadinessResponse response = service.checkContactReadiness(
        campaignId, ContactReadinessRequest.builder().itemIds(List.of(itemId)).build());

    assertThat(response.getManualCount()).isEqualTo(1);
    var itemRes = response.getItems().get(0);
    assertThat(itemRes.getReadinessStatus()).isEqualTo("MANUAL_CONTACT");
    assertThat(itemRes.getContactChannel()).isEqualTo("MANUAL_SOCIAL_LINK");
    assertThat(itemRes.getContactTarget()).isEqualTo("https://tiktok.com/@koc_social");
    assertThat(itemRes.isCanProceed()).isFalse();
  }
}
