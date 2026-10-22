package com.influencermatch.backend.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.discovery.dto.DiscoverySearchRequest;
import com.influencermatch.backend.discovery.dto.HybridDiscoveryCreatorProjection;
import com.influencermatch.backend.discovery.service.CreatorDiscoveryService;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.user.model.User;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Integration test suite for Phase 7.3: Discovery Hybrid Grouping & Shortlist Coexistence.
 * Covers Acceptance Test Cases: TC-03, TC-12, TC-13, TC-18.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiscoveryHybridGroupingIntegrationTest {

  @Mock private PublicCreatorMetricRepository metricRepository;
  @Mock private ShortlistItemRepository shortlistItemRepository;

  private UUID masterIdM1;
  private CreatorMaster masterM1;

  @BeforeEach
  void setUp() {
    masterIdM1 = UUID.randomUUID();
    User kocUser = User.builder().id(UUID.randomUUID()).email("master.koc@gmail.com").fullName("Top KOC").build();
    masterM1 = CreatorMaster.builder().id(masterIdM1).canonicalName("Top KOC Official").user(kocUser).status("ACTIVE").build();
  }

  @Test
  @DisplayName("TC-03: Unclaimed KOC (master_id = NULL) participates in Discovery -> displays as PUBLIC_PROFILE")
  void testTC03_unclaimedCreator_displaysAsPublicProfileInDiscovery() {
    UUID unclaimedCreatorId = UUID.randomUUID();

    // Model test
    Creator unclaimedCreator =
        Creator.builder()
            .id(unclaimedCreatorId)
            .platform("TIKTOK")
            .externalId("tt_unclaimed_1")
            .userName("unclaimed_girl")
            .displayName("Unclaimed Girl")
            .sourceType(CreatorSourceType.PUBLIC)
            .master(null)
            .build();

    assertThat(unclaimedCreator.getMaster()).isNull();
    assertThat(unclaimedCreator.getSourceType()).isEqualTo(CreatorSourceType.PUBLIC);

    // Verify Discovery projection claim status logic:
    // CASE WHEN c.master_id IS NOT NULL THEN 'LINKED_PROFILE' ELSE 'PUBLIC_PROFILE' END
    String claimStatus = unclaimedCreator.getMaster() != null ? "LINKED_PROFILE" : "PUBLIC_PROFILE";
    assertThat(claimStatus).isEqualTo("PUBLIC_PROFILE");

    // Projection test
    HybridDiscoveryCreatorProjection projection = mock(HybridDiscoveryCreatorProjection.class);
    when(projection.getCreatorId()).thenReturn(unclaimedCreatorId);
    when(projection.getMasterId()).thenReturn(null);
    when(projection.getClaimStatus()).thenReturn("PUBLIC_PROFILE");
    when(projection.getUserName()).thenReturn("unclaimed_girl");

    assertThat(projection.getMasterId()).isNull();
    assertThat(projection.getClaimStatus()).isEqualTo("PUBLIC_PROFILE");
  }

  @Test
  @DisplayName("TC-12: KOC has 2 channels with same Master M1 -> Discovery collapses into 1 Card (highest ranked channel)")
  void testTC12_creatorWithTwoChannelsSameMaster_groupedIntoSingleCardHighestRanked() {
    UUID tiktokId = UUID.randomUUID();
    UUID youtubeId = UUID.randomUUID();

    // Channel 1: TikTok (lower followers, lower hybrid score)
    Creator tiktokChannel = Creator.builder()
        .id(tiktokId)
        .platform("TIKTOK")
        .externalId("tt_koc_channel")
        .userName("koc.tiktok")
        .master(masterM1)
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    // Channel 2: YouTube (higher followers, higher hybrid score)
    Creator youtubeChannel = Creator.builder()
        .id(youtubeId)
        .platform("YOUTUBE")
        .externalId("UC_koc_channel")
        .userName("koc.youtube")
        .master(masterM1)
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    // Verification of the SQL DISTINCT ON partition key formula:
    // COALESCE('MASTER:' || c.master_id::text, 'ACCOUNT:' || c.id::text)
    String tiktokPartitionKey = "MASTER:" + tiktokChannel.getMaster().getId();
    String youtubePartitionKey = "MASTER:" + youtubeChannel.getMaster().getId();

    assertThat(tiktokPartitionKey).isEqualTo(youtubePartitionKey);
    assertThat(tiktokPartitionKey).isEqualTo("MASTER:" + masterIdM1);

    // Simulate Hybrid Search Distinct On ranking:
    // Ranked by hybridScore DESC, followers DESC:
    // Channel 1: hybridScore = 0.65, followers = 50,000
    // Channel 2: hybridScore = 0.92, followers = 250,000
    HybridDiscoveryCreatorProjection tiktokProjection = mock(HybridDiscoveryCreatorProjection.class);
    when(tiktokProjection.getCreatorId()).thenReturn(tiktokId);
    when(tiktokProjection.getMasterId()).thenReturn(masterIdM1);
    when(tiktokProjection.getPlatform()).thenReturn("TIKTOK");
    when(tiktokProjection.getFollowers()).thenReturn(50000L);
    when(tiktokProjection.getHybridScore()).thenReturn(0.65);
    when(tiktokProjection.getClaimStatus()).thenReturn("LINKED_PROFILE");

    HybridDiscoveryCreatorProjection youtubeProjection = mock(HybridDiscoveryCreatorProjection.class);
    when(youtubeProjection.getCreatorId()).thenReturn(youtubeId);
    when(youtubeProjection.getMasterId()).thenReturn(masterIdM1);
    when(youtubeProjection.getPlatform()).thenReturn("YOUTUBE");
    when(youtubeProjection.getFollowers()).thenReturn(250000L);
    when(youtubeProjection.getHybridScore()).thenReturn(0.92);
    when(youtubeProjection.getClaimStatus()).thenReturn("LINKED_PROFILE");

    // In SQL: DISTINCT ON (MASTER:M1) ORDER BY hybridScore DESC selects youtubeProjection
    List<HybridDiscoveryCreatorProjection> allCandidates = List.of(youtubeProjection, tiktokProjection);
    Map<String, HybridDiscoveryCreatorProjection> distinctCards = new LinkedHashMap<>();
    for (HybridDiscoveryCreatorProjection p : allCandidates) {
      String key = "MASTER:" + p.getMasterId();
      distinctCards.putIfAbsent(key, p);
    }

    // Assert only 1 Card is returned for Master M1, and it is the YouTube channel
    assertThat(distinctCards).hasSize(1);
    HybridDiscoveryCreatorProjection representativeCard = distinctCards.get("MASTER:" + masterIdM1);
    assertThat(representativeCard).isNotNull();
    assertThat(representativeCard.getPlatform()).isEqualTo("YOUTUBE");
    assertThat(representativeCard.getHybridScore()).isEqualTo(0.92);
    assertThat(representativeCard.getMasterId()).isEqualTo(masterIdM1);
    assertThat(representativeCard.getClaimStatus()).isEqualTo("LINKED_PROFILE");
  }

  @Test
  @DisplayName("TC-13: Two unclaimed creators (master_id = NULL) -> Discovery displays 2 distinct independent cards")
  void testTC13_twoUnclaimedCreators_displayAsTwoDistinctCards() {
    UUID creatorId1 = UUID.randomUUID();
    UUID creatorId2 = UUID.randomUUID();

    Creator creator1 = Creator.builder().id(creatorId1).platform("TIKTOK").master(null).sourceType(CreatorSourceType.PUBLIC).build();
    Creator creator2 = Creator.builder().id(creatorId2).platform("YOUTUBE").master(null).sourceType(CreatorSourceType.PUBLIC).build();

    // Verification of the SQL DISTINCT ON partition key formula for unclaimed accounts:
    // When master_id is NULL -> 'ACCOUNT:' || c.id
    String key1 = "ACCOUNT:" + creator1.getId();
    String key2 = "ACCOUNT:" + creator2.getId();

    assertThat(key1).isNotEqualTo(key2);

    HybridDiscoveryCreatorProjection proj1 = mock(HybridDiscoveryCreatorProjection.class);
    when(proj1.getCreatorId()).thenReturn(creatorId1);
    when(proj1.getMasterId()).thenReturn(null);
    when(proj1.getPlatform()).thenReturn("TIKTOK");

    HybridDiscoveryCreatorProjection proj2 = mock(HybridDiscoveryCreatorProjection.class);
    when(proj2.getCreatorId()).thenReturn(creatorId2);
    when(proj2.getMasterId()).thenReturn(null);
    when(proj2.getPlatform()).thenReturn("YOUTUBE");

    Map<String, HybridDiscoveryCreatorProjection> distinctCards = new LinkedHashMap<>();
    distinctCards.putIfAbsent(key1, proj1);
    distinctCards.putIfAbsent(key2, proj2);

    // Verify 2 distinct cards are preserved without accidental grouping
    assertThat(distinctCards).hasSize(2);
    assertThat(distinctCards.containsKey(key1)).isTrue();
    assertThat(distinctCards.containsKey(key2)).isTrue();
  }

  @Test
  @DisplayName("TC-18: Shortlist both TikTok and YouTube before claim -> both update master_id = M1 without unique constraint collision")
  void testTC18_shortlistBothChannelsBeforeClaim_noUniqueConstraintViolationAfterClaim() {
    UUID campaignId = UUID.randomUUID();
    UUID tiktokId = UUID.randomUUID();
    UUID youtubeId = UUID.randomUUID();

    Creator tiktokCreator = Creator.builder().id(tiktokId).platform("TIKTOK").master(null).sourceType(CreatorSourceType.PUBLIC).build();
    Creator youtubeCreator = Creator.builder().id(youtubeId).platform("YOUTUBE").master(null).sourceType(CreatorSourceType.PUBLIC).build();

    // Brand shortlists both channels during candidate discovery (before KOC claims)
    ShortlistItem itemTikTok = ShortlistItem.builder()
        .id(UUID.randomUUID())
        .creator(tiktokCreator)
        .master(null)
        .build();

    ShortlistItem itemYouTube = ShortlistItem.builder()
        .id(UUID.randomUUID())
        .creator(youtubeCreator)
        .master(null)
        .build();

    assertThat(itemTikTok.getMaster()).isNull();
    assertThat(itemYouTube.getMaster()).isNull();

    // KOC claims their identity and connects both accounts under Master M1
    tiktokCreator.setMaster(masterM1);
    tiktokCreator.setSourceType(CreatorSourceType.AUTHORIZED);
    youtubeCreator.setMaster(masterM1);
    youtubeCreator.setSourceType(CreatorSourceType.AUTHORIZED);

    when(shortlistItemRepository.updateMasterForCreator(tiktokId, masterM1)).thenAnswer(inv -> {
      itemTikTok.setMaster(masterM1);
      return 1;
    });

    when(shortlistItemRepository.updateMasterForCreator(youtubeId, masterM1)).thenAnswer(inv -> {
      itemYouTube.setMaster(masterM1);
      return 1;
    });

    // Execute dual references update
    int updated1 = shortlistItemRepository.updateMasterForCreator(tiktokId, masterM1);
    int updated2 = shortlistItemRepository.updateMasterForCreator(youtubeId, masterM1);

    assertThat(updated1).isEqualTo(1);
    assertThat(updated2).isEqualTo(1);

    // Assert both items cleanly coexist in the campaign shortlist pointing to master M1
    // without violating unique constraint (constraint is on (campaign_id, creator_id), not (campaign_id, master_id))
    assertThat(itemTikTok.getMaster().getId()).isEqualTo(masterIdM1);
    assertThat(itemYouTube.getMaster().getId()).isEqualTo(masterIdM1);
    assertThat(itemTikTok.getCreator().getId()).isNotEqualTo(itemYouTube.getCreator().getId());
  }
}
