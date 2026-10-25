package com.influencermatch.backend.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.relationship.dto.AddShortlistItemRequest;
import com.influencermatch.backend.relationship.dto.ShortlistItemDto;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistSource;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.relationship.service.ShortlistServiceImpl;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class ShortlistCartPhase1Test {

  private ShortlistItemRepository shortlistItemRepository;
  private CampaignOwnershipService ownership;
  private CreatorRepository creatorRepository;
  private UserRepository userRepository;
  private RecommendationRunRepository recommendationRunRepository;
  private ShortlistServiceImpl shortlistService;

  private User brandUser;
  private Campaign campaign;
  private Creator creator;
  private UUID campaignId;
  private UUID creatorId;

  @BeforeEach
  void setUp() {
    shortlistItemRepository = mock(ShortlistItemRepository.class);
    ownership = mock(CampaignOwnershipService.class);
    creatorRepository = mock(CreatorRepository.class);
    userRepository = mock(UserRepository.class);
    recommendationRunRepository = mock(RecommendationRunRepository.class);

    shortlistService = new ShortlistServiceImpl(
        shortlistItemRepository,
        ownership,
        creatorRepository,
        userRepository,
        recommendationRunRepository
    );

    brandUser = TestActors.user(Role.BRAND);
    TestActors.authenticate(brandUser);

    campaignId = UUID.randomUUID();
    creatorId = UUID.randomUUID();

    campaign = Campaign.builder()
        .id(campaignId)
        .build();

    creator = Creator.builder()
        .id(creatorId)
        .displayName("Trang Dao")
        .userName("trangdao_review")
        .build();

    when(ownership.requireOwned(campaignId)).thenReturn(campaign);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Cart Add: First time adding creator returns ADDED status with total count")
  void testAddCreatorToShortlist_firstTime() {
    when(shortlistItemRepository.findByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(Optional.empty());
    when(creatorRepository.findById(creatorId)).thenReturn(Optional.of(creator));
    when(userRepository.findById(brandUser.getId())).thenReturn(Optional.of(brandUser));
    when(shortlistItemRepository.countByCampaignIdAndStatusNot(campaignId, ShortlistStatus.REMOVED))
        .thenReturn(1L);

    when(shortlistItemRepository.save(any(ShortlistItem.class))).thenAnswer(inv -> {
      ShortlistItem item = inv.getArgument(0);
      item.setId(UUID.randomUUID());
      return item;
    });

    AddShortlistItemRequest req = AddShortlistItemRequest.builder()
        .creatorId(creatorId)
        .priority(ShortlistPriority.HIGH)
        .source(ShortlistSource.AI_RECOMMENDATION)
        .note("Promising lifestyle creator")
        .build();

    ShortlistItemDto result = shortlistService.addCreatorToShortlist(campaignId, req);

    assertThat(result.getAction()).isEqualTo("ADDED");
    assertThat(result.getIsInShortlist()).isTrue();
    assertThat(result.getStatus()).isEqualTo(ShortlistStatus.SAVED);
    assertThat(result.getSource()).isEqualTo(ShortlistSource.AI_RECOMMENDATION);
    assertThat(result.getTotalShortlistCount()).isEqualTo(1L);
    assertThat(result.getMessage()).contains("Trang Dao");

    verify(shortlistItemRepository).save(any(ShortlistItem.class));
  }

  @Test
  @DisplayName("Cart Add Idempotent: Second time adding already existing creator returns ALREADY_EXISTS, does NOT throw 409")
  void testAddCreatorToShortlist_idempotentSecondTime() {
    ShortlistItem existing = ShortlistItem.builder()
        .id(UUID.randomUUID())
        .campaign(campaign)
        .creator(creator)
        .user(brandUser)
        .priority(ShortlistPriority.MEDIUM)
        .status(ShortlistStatus.SAVED)
        .source(ShortlistSource.AI_RECOMMENDATION)
        .note("Existing note from brand")
        .addedAt(LocalDateTime.now().minusHours(2))
        .build();

    when(shortlistItemRepository.findByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(Optional.of(existing));
    when(shortlistItemRepository.countByCampaignIdAndStatusNot(campaignId, ShortlistStatus.REMOVED))
        .thenReturn(5L);
    when(shortlistItemRepository.save(any(ShortlistItem.class))).thenAnswer(inv -> inv.getArgument(0));

    AddShortlistItemRequest req = AddShortlistItemRequest.builder()
        .creatorId(creatorId)
        .priority(ShortlistPriority.HIGH)
        .build();

    ShortlistItemDto result = shortlistService.addCreatorToShortlist(campaignId, req);

    assertThat(result.getAction()).isEqualTo("ALREADY_EXISTS");
    assertThat(result.getIsInShortlist()).isTrue();
    assertThat(result.getPriority()).isEqualTo(ShortlistPriority.HIGH);
    assertThat(result.getTotalShortlistCount()).isEqualTo(5L);
    assertThat(result.getMessage()).isEqualTo("Ứng viên đã có sẵn trong Shortlist");

    verify(shortlistItemRepository).save(existing);
    verifyNoInteractions(creatorRepository);
  }

  @Test
  @DisplayName("Cart Remove: Soft delete sets status to REMOVED instead of hard deleting")
  void testRemoveCreatorFromShortlist_softRemove() {
    UUID shortlistItemId = UUID.randomUUID();
    ShortlistItem item = ShortlistItem.builder()
        .id(shortlistItemId)
        .campaign(campaign)
        .creator(creator)
        .user(brandUser)
        .status(ShortlistStatus.SAVED)
        .addedAt(LocalDateTime.now())
        .build();

    when(shortlistItemRepository.findByIdAndCampaignIdAndUserId(shortlistItemId, campaignId, brandUser.getId()))
        .thenReturn(Optional.of(item));

    shortlistService.removeCreatorFromShortlist(campaignId, shortlistItemId);

    assertThat(item.getStatus()).isEqualTo(ShortlistStatus.REMOVED);
    verify(shortlistItemRepository).save(item);
    verify(shortlistItemRepository, never()).delete(any());
  }

  @Test
  @DisplayName("Cart Resurrect: Adding an item previously REMOVED sets status back to SAVED")
  void testAddCreatorToShortlist_resurrectRemovedItem() {
    ShortlistItem removedItem = ShortlistItem.builder()
        .id(UUID.randomUUID())
        .campaign(campaign)
        .creator(creator)
        .user(brandUser)
        .priority(ShortlistPriority.LOW)
        .status(ShortlistStatus.REMOVED)
        .addedAt(LocalDateTime.now().minusDays(1))
        .build();

    when(shortlistItemRepository.findByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(Optional.of(removedItem));
    when(shortlistItemRepository.countByCampaignIdAndStatusNot(campaignId, ShortlistStatus.REMOVED))
        .thenReturn(3L);
    when(shortlistItemRepository.save(any(ShortlistItem.class))).thenAnswer(inv -> inv.getArgument(0));

    AddShortlistItemRequest req = AddShortlistItemRequest.builder()
        .creatorId(creatorId)
        .priority(ShortlistPriority.MEDIUM)
        .note("Re-adding to campaign")
        .build();

    ShortlistItemDto result = shortlistService.addCreatorToShortlist(campaignId, req);

    assertThat(removedItem.getStatus()).isEqualTo(ShortlistStatus.SAVED);
    assertThat(removedItem.getNote()).isEqualTo("Re-adding to campaign");
    assertThat(result.getAction()).isEqualTo("ALREADY_EXISTS");
    assertThat(result.getIsInShortlist()).isTrue();
    verify(shortlistItemRepository).save(removedItem);
  }
}
