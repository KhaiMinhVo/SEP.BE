package com.influencermatch.backend.creator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.creator.controller.CreatorPortalAccountController;
import com.influencermatch.backend.creator.controller.CreatorPortalAnalyticsController;
import com.influencermatch.backend.creator.dto.AggregatedReachDto;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.oauth.OAuthTokenEncryptionService;
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

class CreatorPortalReachPhase5Test {

  private CreatorRepository creatorRepository;
  private CreatorMasterRepository creatorMasterRepository;
  private CreatorIdentityConflictRepository conflictRepository;
  private CreatorAccountVerificationRepository accountVerificationRepository;
  private CreatorProviderIdentityRepository providerIdentityRepository;
  private CreatorIdentityAuditRepository identityAuditRepository;
  private ShortlistItemRepository shortlistItemRepository;
  private BrandCreatorRelationshipRepository relationshipRepository;
  private UserRepository userRepository;
  private OAuthTokenEncryptionService tokenEncryptionService;
  private OAuthProofClient oAuthProofClient;
  private PublicCreatorMetricRepository publicCreatorMetricRepository;

  private CreatorAccountLinkService accountLinkService;
  private CreatorPortalAnalyticsController analyticsController;
  private CreatorPortalAccountController accountController;

  private User creatorUser;
  private UUID userId;
  private CreatorMaster master;

  @BeforeEach
  void setUp() {
    creatorRepository = mock(CreatorRepository.class);
    creatorMasterRepository = mock(CreatorMasterRepository.class);
    conflictRepository = mock(CreatorIdentityConflictRepository.class);
    accountVerificationRepository = mock(CreatorAccountVerificationRepository.class);
    providerIdentityRepository = mock(CreatorProviderIdentityRepository.class);
    identityAuditRepository = mock(CreatorIdentityAuditRepository.class);
    shortlistItemRepository = mock(ShortlistItemRepository.class);
    relationshipRepository = mock(BrandCreatorRelationshipRepository.class);
    userRepository = mock(UserRepository.class);
    tokenEncryptionService = mock(OAuthTokenEncryptionService.class);
    oAuthProofClient = mock(OAuthProofClient.class);
    publicCreatorMetricRepository = mock(PublicCreatorMetricRepository.class);

    accountLinkService =
        new CreatorAccountLinkService(
            creatorRepository,
            creatorMasterRepository,
            conflictRepository,
            accountVerificationRepository,
            providerIdentityRepository,
            identityAuditRepository,
            shortlistItemRepository,
            relationshipRepository,
            userRepository,
            tokenEncryptionService,
            oAuthProofClient,
            publicCreatorMetricRepository);

    analyticsController = new CreatorPortalAnalyticsController(accountLinkService);
    accountController = new CreatorPortalAccountController(accountLinkService, oAuthProofClient);

    creatorUser = TestActors.user(Role.CREATOR);
    userId = creatorUser.getId();
    TestActors.authenticate(creatorUser);

    master =
        CreatorMaster.builder()
            .user(creatorUser)
            .canonicalName("Nguyen Van A Official")
            .status("ACTIVE")
            .build();
    master.setId(UUID.randomUUID());

    when(creatorMasterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("PHASE 5: Aggregated Reach calculates combined followers, views, and weighted ER across multiple channels")
  void testAggregatedReach_MultiChannel_AccurateMetrics() {
    // Channel 1: TikTok (50k followers, 20k views, ER 0.0500)
    UUID tiktokId = UUID.randomUUID();
    Creator tiktokChannel =
        Creator.builder()
            .id(tiktokId)
            .platform("TIKTOK")
            .userName("koc_tiktok")
            .displayName("KOC TikTok")
            .sourceType(CreatorSourceType.AUTHORIZED)
            .master(master)
            .authorizedAt(LocalDateTime.now().minusDays(10))
            .build();

    PublicCreatorMetric tiktokMetric =
        PublicCreatorMetric.builder()
            .creator(tiktokChannel)
            .followers(50_000L)
            .avgViews(BigDecimal.valueOf(20_000))
            .engagementRate(new BigDecimal("0.0500"))
            .build();

    CreatorAccountVerification tiktokVer =
        CreatorAccountVerification.builder()
            .creator(tiktokChannel)
            .verificationMethod("OAUTH_OFFICIAL_SANDBOX")
            .status("ACTIVE")
            .tokenExpiresAt(LocalDateTime.now().plusDays(30))
            .build();

    // Channel 2: YouTube (150k followers, 60k views, ER 0.0300)
    UUID ytId = UUID.randomUUID();
    Creator ytChannel =
        Creator.builder()
            .id(ytId)
            .platform("YOUTUBE")
            .userName("koc_youtube")
            .displayName("KOC YouTube Channel")
            .sourceType(CreatorSourceType.AUTHORIZED)
            .master(master)
            .authorizedAt(LocalDateTime.now().minusDays(5))
            .build();

    PublicCreatorMetric ytMetric =
        PublicCreatorMetric.builder()
            .creator(ytChannel)
            .followers(150_000L)
            .avgViews(BigDecimal.valueOf(60_000))
            .engagementRate(new BigDecimal("0.0300"))
            .build();

    CreatorAccountVerification ytVer =
        CreatorAccountVerification.builder()
            .creator(ytChannel)
            .verificationMethod("OAUTH_TESTING_GOOGLE")
            .status("ACTIVE")
            .tokenExpiresAt(LocalDateTime.now().plusDays(15))
            .build();

    when(creatorRepository.findByMasterId(master.getId()))
        .thenReturn(List.of(tiktokChannel, ytChannel));
    when(publicCreatorMetricRepository.findByCreatorId(tiktokId))
        .thenReturn(Optional.of(tiktokMetric));
    when(publicCreatorMetricRepository.findByCreatorId(ytId))
        .thenReturn(Optional.of(ytMetric));
    when(accountVerificationRepository.findFirstByCreatorIdOrderByVerifiedAtDesc(tiktokId))
        .thenReturn(Optional.of(tiktokVer));
    when(accountVerificationRepository.findFirstByCreatorIdOrderByVerifiedAtDesc(ytId))
        .thenReturn(Optional.of(ytVer));

    AggregatedReachDto reach = accountLinkService.getAggregatedReach(userId);

    assertThat(reach.getMasterId()).isEqualTo(master.getId());
    assertThat(reach.getCanonicalName()).isEqualTo("Nguyen Van A Official");
    assertThat(reach.getChannelCount()).isEqualTo(2);
    // Total followers: 50,000 + 150,000 = 200,000
    assertThat(reach.getTotalFollowers()).isEqualTo(200_000L);
    // Total avgViews: 20,000 + 60,000 = 80,000
    assertThat(reach.getTotalAvgViews()).isEqualByComparingTo(BigDecimal.valueOf(80_000));
    // Weighted ER: (50,000 * 0.0500 + 150,000 * 0.0300) / 200,000 = (2500 + 4500) / 200000 = 0.0350
    assertThat(reach.getAverageEngagementRate()).isEqualByComparingTo(new BigDecimal("0.0350"));
    assertThat(reach.getPlatforms()).containsExactly("TIKTOK", "YOUTUBE");
    assertThat(reach.getPlatformBreakdown()).hasSize(2);
    assertThat(reach.getPlatformBreakdown()).allMatch(c -> "ACTIVE".equals(c.getTokenStatus()));
  }

  @Test
  @DisplayName("PHASE 5: Expired token is detected and flagged as EXPIRED in platformBreakdown")
  void testAggregatedReach_ExpiredToken() {
    UUID channelId = UUID.randomUUID();
    Creator channel =
        Creator.builder()
            .id(channelId)
            .platform("TIKTOK")
            .userName("expired_koc")
            .sourceType(CreatorSourceType.AUTHORIZED)
            .master(master)
            .build();

    CreatorAccountVerification expiredVer =
        CreatorAccountVerification.builder()
            .creator(channel)
            .status("ACTIVE")
            .tokenExpiresAt(LocalDateTime.now().minusHours(2)) // expired 2 hours ago
            .build();

    when(creatorRepository.findByMasterId(master.getId())).thenReturn(List.of(channel));
    when(publicCreatorMetricRepository.findByCreatorId(channelId)).thenReturn(Optional.empty());
    when(accountVerificationRepository.findFirstByCreatorIdOrderByVerifiedAtDesc(channelId))
        .thenReturn(Optional.of(expiredVer));

    AggregatedReachDto reach = accountLinkService.getAggregatedReach(userId);

    assertThat(reach.getChannelCount()).isEqualTo(1);
    assertThat(reach.getPlatformBreakdown().get(0).getTokenStatus()).isEqualTo("EXPIRED");
  }

  @Test
  @DisplayName("PHASE 5: Master with no channels returns empty breakdown and zero totals")
  void testAggregatedReach_EmptyChannels() {
    when(creatorRepository.findByMasterId(master.getId())).thenReturn(Collections.emptyList());

    AggregatedReachDto reach = accountLinkService.getAggregatedReach(userId);

    assertThat(reach.getChannelCount()).isEqualTo(0);
    assertThat(reach.getTotalFollowers()).isEqualTo(0L);
    assertThat(reach.getTotalAvgViews()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(reach.getAverageEngagementRate()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(reach.getPlatformBreakdown()).isEmpty();
  }

  @Test
  @DisplayName("PHASE 5: Controllers return 200 OK with reach payload")
  void testAggregatedReach_Controllers() {
    when(creatorRepository.findByMasterId(master.getId())).thenReturn(Collections.emptyList());

    ApiResponse<AggregatedReachDto> analyticsResp = analyticsController.getAggregatedReach();
    assertThat(analyticsResp.success()).isTrue();
    assertThat(analyticsResp.data().getMasterId()).isEqualTo(master.getId());

    ApiResponse<AggregatedReachDto> accountResp = accountController.getAggregatedReach();
    assertThat(accountResp.success()).isTrue();
    assertThat(accountResp.data().getMasterId()).isEqualTo(master.getId());
  }
}
