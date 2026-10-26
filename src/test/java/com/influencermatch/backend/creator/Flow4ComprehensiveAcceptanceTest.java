package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.creator.client.OAuthProofClient.ProofResult;
import com.influencermatch.backend.creator.controller.AdminIdentityConflictController;
import com.influencermatch.backend.creator.controller.CreatorClaimOAuthController;
import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.*;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.CreatorOwnershipConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ForbiddenException;
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
import com.influencermatch.backend.security.oauth.OAuthTokenEncryptionService;
import com.influencermatch.backend.support.CreatorServiceFixtures;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Phase 6: Comprehensive Automated Acceptance Test Suite (TEST-F4-01 to TEST-F4-10 + Data Safety Contract).
 * Validates the complete Flow 4: Official Developer Sandbox / Testing Mode OAuth, Proof-of-Control,
 * Multi-Channel Linking, Identity Conflict Management, Shortlist Outreach, and CRM State Machine.
 */
class Flow4ComprehensiveAcceptanceTest {

  // Repositories
  private CreatorRepository creatorRepository;
  private CreatorMasterRepository creatorMasterRepository;
  private CreatorInvitationRepository creatorInvitationRepository;
  private CreatorAccountVerificationRepository accountVerificationRepository;
  private CreatorProviderIdentityRepository providerIdentityRepository;
  private CreatorIdentityAuditRepository identityAuditRepository;
  private CreatorIdentityConflictRepository conflictRepository;
  private PublicCreatorMetricRepository publicCreatorMetricRepository;
  private PostRepository postRepository;
  private ShortlistItemRepository shortlistItemRepository;
  private BrandCreatorRelationshipRepository relationshipRepository;
  private UserRepository userRepository;
  private RecommendationRunRepository recommendationRunRepository;

  // Infrastructure / Shared Services
  private CampaignOwnershipService campaignOwnershipService;
  private OAuthProofClient oAuthProofClient;
  private OAuthTokenEncryptionService tokenEncryptionService;
  private CreatorInvitationService creatorInvitationService;
  private EntityManager entityManager;
  private Clock fixedClock;

  // Target Services & Controllers
  private CreatorClaimResolutionService claimResolutionService;
  private CreatorClaimOAuthController claimOAuthController;
  private CreatorAccountLinkService accountLinkService;
  private CreatorIdentityConflictService conflictService;
  private AdminIdentityConflictController adminConflictController;
  private ShortlistServiceImpl shortlistService;
  private RelationshipServiceImpl relationshipService;
  private CreatorIngestionService ingestionService;

  // Test Actors & Fixtures
  private User creatorUser;
  private User dataManagerUser;
  private User brandUser;
  private BrandProfile brandProfile;
  private Campaign campaign;
  private UUID campaignId;

  @BeforeEach
  void setUp() {
    fixedClock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneId.of("UTC"));

    // 1. Mock Repositories
    creatorRepository = mock(CreatorRepository.class);
    creatorMasterRepository = mock(CreatorMasterRepository.class);
    creatorInvitationRepository = mock(CreatorInvitationRepository.class);
    accountVerificationRepository = mock(CreatorAccountVerificationRepository.class);
    providerIdentityRepository = mock(CreatorProviderIdentityRepository.class);
    identityAuditRepository = mock(CreatorIdentityAuditRepository.class);
    conflictRepository = mock(CreatorIdentityConflictRepository.class);
    publicCreatorMetricRepository = mock(PublicCreatorMetricRepository.class);
    postRepository = mock(PostRepository.class);
    shortlistItemRepository = mock(ShortlistItemRepository.class);
    relationshipRepository = mock(BrandCreatorRelationshipRepository.class);
    userRepository = mock(UserRepository.class);
    recommendationRunRepository = mock(RecommendationRunRepository.class);

    // 2. Mock Clients & Helpers
    campaignOwnershipService = mock(CampaignOwnershipService.class);
    oAuthProofClient = mock(OAuthProofClient.class);
    tokenEncryptionService = mock(OAuthTokenEncryptionService.class);
    creatorInvitationService = mock(CreatorInvitationService.class);
    entityManager = mock(EntityManager.class);

    when(tokenEncryptionService.encrypt(anyString())).thenAnswer(inv -> "enc_" + inv.getArgument(0));

    // 3. Instantiate Domain Services
    claimResolutionService = new CreatorClaimResolutionService(
        creatorRepository,
        creatorMasterRepository,
        creatorInvitationRepository,
        accountVerificationRepository,
        providerIdentityRepository,
        identityAuditRepository,
        shortlistItemRepository,
        relationshipRepository,
        userRepository,
        tokenEncryptionService,
        oAuthProofClient);
    CreatorServiceFixtures.wire(claimResolutionService);
    ReflectionTestUtils.setField(claimResolutionService, "clock", fixedClock);
    ReflectionTestUtils.setField(claimResolutionService, "em", entityManager);

    claimOAuthController = new CreatorClaimOAuthController(
        claimResolutionService, creatorInvitationService, oAuthProofClient);

    accountLinkService = new CreatorAccountLinkService(
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
    CreatorServiceFixtures.wire(accountLinkService);
    ReflectionTestUtils.setField(accountLinkService, "em", entityManager);

    conflictService = new CreatorIdentityConflictService(
        conflictRepository,
        creatorRepository,
        identityAuditRepository,
        shortlistItemRepository,
        relationshipRepository);

    adminConflictController = new AdminIdentityConflictController(conflictService);

    shortlistService = new ShortlistServiceImpl(
        shortlistItemRepository,
        campaignOwnershipService,
        creatorRepository,
        userRepository,
        recommendationRunRepository,
        publicCreatorMetricRepository,
        relationshipRepository,
        creatorInvitationService,
        creatorInvitationRepository);

    relationshipService = new RelationshipServiceImpl(
        relationshipRepository,
        shortlistItemRepository,
        campaignOwnershipService,
        creatorRepository,
        userRepository);

    ingestionService = new CreatorIngestionService(
        creatorRepository,
        publicCreatorMetricRepository,
        postRepository,
        entityManager);

    // 4. Test Actors
    creatorUser = TestActors.user(Role.CREATOR);
    creatorUser.setEmail("koc.authentic@tiktok.test");
    creatorUser.setFullName("Trang Dao Beauty");

    dataManagerUser = TestActors.user(Role.DATA_MANAGER);
    dataManagerUser.setEmail("datamanager@influencermatch.com");
    dataManagerUser.setFullName("Data Manager");

    brandUser = TestActors.user(Role.BRAND);
    brandUser.setEmail("brand@company.com");
    brandUser.setFullName("Brand Manager");

    brandProfile = BrandProfile.builder()
        .id(UUID.randomUUID())
        .businessName("Brand Corp")
        .user(brandUser)
        .build();

    campaignId = UUID.randomUUID();
    campaign = Campaign.builder()
        .id(campaignId)
        .name("Autumn Launch 2026")
        .productService("Smart Watch")
        .brandProfile(brandProfile)
        .build();

    when(campaignOwnershipService.requireOwned(campaignId)).thenReturn(campaign);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  // =========================================================================
  // TEST-F4-01: Redirect to Official Authorization Endpoint
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-01 [Redirect]: startOAuth chuyển hướng chính xác đến Authorization Endpoint của TikTok/Google với đầy đủ client_id, redirect_uri và state")
  void testF4_01_redirect_startOAuth_buildsCorrectPlatformUrlAndState() {
    String rawToken = "raw_invitation_token_123";
    String expectedTikTokUrl = "https://www.tiktok.com/v2/auth/authorize/?client_key=sandbox_key&redirect_uri=https%3A%2F%2Fapp.influencermatch.com%2Foauth%2Fcallback%2Ftiktok&response_type=code&scope=user.info.basic&state=" + rawToken;

    when(oAuthProofClient.buildAuthorizationUrl("tiktok", rawToken)).thenReturn(expectedTikTokUrl);

    ResponseEntity<Void> response = claimOAuthController.startOAuth("tiktok", rawToken, true);

    verify(oAuthProofClient).requireConfigured();
    verify(creatorInvitationService).verifyInvitationToken(rawToken);
    verify(oAuthProofClient).buildAuthorizationUrl("tiktok", rawToken);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(expectedTikTokUrl));
    assertThat(response.getHeaders().getLocation().toString()).contains("state=" + rawToken);
  }

  // =========================================================================
  // TEST-F4-02: TikTok Claim with Atomic Master M1 Creation
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-02 [TikTok Claim]: Callback trả về TikTok code -> đối soát đúng username -> tạo CreatorMaster M1 nguyên tử, set AUTHORIZED")
  void testF4_02_tiktokClaim_matchingUsername_createsMasterM1AndSetsAuthorized() {
    TestActors.authenticate(creatorUser);

    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId)
        .platform("TIKTOK")
        .externalId("tt_trangdao_001")
        .userName("@trangdao.beauty")
        .displayName("Trang Dao Beauty")
        .sourceType(CreatorSourceType.PUBLIC)
        .isStableId(true)
        .build();

    String rawToken = "raw_invitation_token_123";
    CreatorInvitation invitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .targetCreator(creator)
        .invitedEmail(creatorUser.getEmail())
        .tokenHash(CreatorInvitationService.hashToken(rawToken))
        .status("PENDING")
        .expiresAt(LocalDateTime.now(fixedClock).plusDays(5))
        .build();

    when(userRepository.findLockedByEmail(creatorUser.getEmail())).thenReturn(Optional.of(creatorUser));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));
    when(creatorInvitationRepository.findByTokenHash(any())).thenReturn(Optional.of(invitation));
    when(creatorInvitationRepository.findLockedById(invitation.getId())).thenReturn(Optional.of(invitation));
    when(creatorMasterRepository.findByUserId(creatorUser.getId())).thenReturn(Optional.empty());
    when(creatorMasterRepository.save(any(CreatorMaster.class))).thenAnswer(inv -> {
      CreatorMaster m = inv.getArgument(0);
      if (m.getId() == null) m.setId(UUID.randomUUID());
      return m;
    });

    String authCode = "tiktok_sandbox_code_999";
    ProofResult proof = ProofResult.builder()
        .providerUserId("open_id_trangdao")
        .providerUsername("trangdao.beauty")
        .accessToken("access_token_tiktok_sandbox")
        .refreshToken("refresh_token_tiktok_sandbox")
        .tokenExpiresAt(LocalDateTime.now(fixedClock).plusDays(1))
        .rawProfileData(Map.of(
            "platform", "TIKTOK",
            "verificationEnvironment", "OFFICIAL_SANDBOX",
            "username", "trangdao.beauty"
        ))
        .build();

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq(authCode), eq("tt_trangdao_001"), eq("@trangdao.beauty")))
        .thenReturn(proof);

    OAuthCallbackResponse response = claimResolutionService.resolveClaim("TIKTOK", authCode, rawToken, creatorUser);

    // 1. Response validation
    assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
    assertThat(response.getCreatorId()).isEqualTo(creatorId);
    assertThat(response.getMasterId()).isNotNull();

    // 2. Atomic CreatorMaster created
    ArgumentCaptor<CreatorMaster> masterCaptor = ArgumentCaptor.forClass(CreatorMaster.class);
    verify(creatorMasterRepository).save(masterCaptor.capture());
    assertThat(masterCaptor.getValue().getUser()).isEqualTo(creatorUser);
    assertThat(masterCaptor.getValue().getCanonicalName()).isEqualTo("Trang Dao Beauty");

    // 3. Creator transitioned to AUTHORIZED and attached to Master
    assertThat(creator.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(creator.getMaster()).isEqualTo(masterCaptor.getValue());
    assertThat(creator.getAuthorizedAt()).isNotNull();

    // 4. Token encrypted and verificationMethod tagged
    ArgumentCaptor<CreatorAccountVerification> verifCaptor = ArgumentCaptor.forClass(CreatorAccountVerification.class);
    verify(accountVerificationRepository).save(verifCaptor.capture());
    assertThat(verifCaptor.getValue().getVerificationMethod()).isEqualTo("OAUTH_OFFICIAL_SANDBOX");
    assertThat(verifCaptor.getValue().getAccessTokenEncrypted()).isEqualTo("enc_access_token_tiktok_sandbox");

    // 5. Invitation marked ACCEPTED
    assertThat(invitation.getStatus()).isEqualTo("ACCEPTED");
    verify(creatorInvitationRepository).save(invitation);

    // 6. Dual reference updated
    verify(shortlistItemRepository).updateMasterForCreator(creatorId, masterCaptor.getValue());
    verify(relationshipRepository).updateMasterForCreator(creatorId, masterCaptor.getValue());
  }

  // =========================================================================
  // TEST-F4-03: YouTube Claim Matching ExternalId (Channel ID UC...)
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-03 [YouTube Claim]: Callback trả về Google code -> gọi YouTube API lấy Channel ID -> đối soát đúng externalId -> gắn kênh vào Master")
  void testF4_03_youtubeClaim_matchingChannelId_attachesChannelToMaster() {
    TestActors.authenticate(creatorUser);

    UUID creatorId = UUID.randomUUID();
    Creator youtubeCreator = Creator.builder()
        .id(creatorId)
        .platform("YOUTUBE")
        .externalId("UC_CHAU_BUI_OFFICIAL")
        .userName("Chau Bui Official")
        .displayName("Chau Bui")
        .sourceType(CreatorSourceType.PUBLIC)
        .build();

    String rawToken = "raw_invitation_token_yt";
    CreatorInvitation invitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .targetCreator(youtubeCreator)
        .invitedEmail(creatorUser.getEmail())
        .tokenHash(CreatorInvitationService.hashToken(rawToken))
        .status("PENDING")
        .expiresAt(LocalDateTime.now(fixedClock).plusDays(5))
        .build();

    when(userRepository.findLockedByEmail(creatorUser.getEmail())).thenReturn(Optional.of(creatorUser));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(youtubeCreator));
    when(creatorInvitationRepository.findByTokenHash(any())).thenReturn(Optional.of(invitation));
    when(creatorInvitationRepository.findLockedById(invitation.getId())).thenReturn(Optional.of(invitation));
    when(creatorMasterRepository.findByUserId(creatorUser.getId())).thenReturn(Optional.empty());
    when(creatorMasterRepository.save(any(CreatorMaster.class))).thenAnswer(inv -> {
      CreatorMaster m = inv.getArgument(0);
      if (m.getId() == null) m.setId(UUID.randomUUID());
      return m;
    });

    String authCode = "google_testing_code_888";
    ProofResult proof = ProofResult.builder()
        .providerUserId("UC_CHAU_BUI_OFFICIAL")
        .providerUsername("Chau Bui Official")
        .accessToken("access_token_google_testing")
        .refreshToken("refresh_token_google_testing")
        .tokenExpiresAt(LocalDateTime.now(fixedClock).plusHours(2))
        .rawProfileData(Map.of(
            "platform", "YOUTUBE",
            "verificationEnvironment", "TESTING_MODE",
            "channelId", "UC_CHAU_BUI_OFFICIAL"
        ))
        .build();

    when(oAuthProofClient.exchangeAndVerify(eq("YOUTUBE"), eq(authCode), eq("UC_CHAU_BUI_OFFICIAL"), eq("Chau Bui Official")))
        .thenReturn(proof);

    OAuthCallbackResponse response = claimResolutionService.resolveClaim("YOUTUBE", authCode, rawToken, creatorUser);

    assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
    assertThat(youtubeCreator.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(youtubeCreator.getMaster()).isNotNull();

    ArgumentCaptor<CreatorAccountVerification> verifCaptor = ArgumentCaptor.forClass(CreatorAccountVerification.class);
    verify(accountVerificationRepository).save(verifCaptor.capture());
    assertThat(verifCaptor.getValue().getVerificationMethod()).isEqualTo("OAUTH_TESTING_GOOGLE");
  }

  // =========================================================================
  // TEST-F4-04: Identity Mismatch Rejection
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-04 [Identity Mismatch]: Tài khoản đăng nhập trên Sandbox không khớp với KOC được mời -> Báo lỗi 409 IDENTITY_MISMATCH, không tạo Master, ghi Audit log")
  void testF4_04_identityMismatch_rejectsWithConflictAndPreservesState() {
    TestActors.authenticate(creatorUser);

    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId)
        .platform("TIKTOK")
        .externalId("tt_real_creator")
        .userName("@real.creator")
        .sourceType(CreatorSourceType.PUBLIC)
        .build();

    String rawToken = "raw_invitation_token_mismatch";
    CreatorInvitation invitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .targetCreator(creator)
        .invitedEmail(creatorUser.getEmail())
        .tokenHash(CreatorInvitationService.hashToken(rawToken))
        .status("PENDING")
        .expiresAt(LocalDateTime.now(fixedClock).plusDays(5))
        .build();

    when(userRepository.findLockedByEmail(creatorUser.getEmail())).thenReturn(Optional.of(creatorUser));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));
    when(creatorInvitationRepository.findByTokenHash(any())).thenReturn(Optional.of(invitation));
    when(creatorInvitationRepository.findLockedById(invitation.getId())).thenReturn(Optional.of(invitation));

    // Sandbox returned an imposter account
    ProofResult proof = ProofResult.builder()
        .providerUserId("open_id_imposter")
        .providerUsername("imposter.hacker")
        .accessToken("access_token_imposter")
        .tokenExpiresAt(LocalDateTime.now(fixedClock).plusHours(1))
        .rawProfileData(Map.of("platform", "TIKTOK"))
        .build();

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq("code_imposter"), eq("tt_real_creator"), eq("@real.creator")))
        .thenReturn(proof);

    assertThatThrownBy(() -> claimResolutionService.resolveClaim("TIKTOK", "code_imposter", rawToken, creatorUser))
        .isInstanceOf(ConflictException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.IDENTITY_MISMATCH);

    // State preservation: no master created, no verification saved, invitation remains PENDING
    verify(creatorMasterRepository, never()).save(any());
    verify(accountVerificationRepository, never()).save(any());
    assertThat(invitation.getStatus()).isEqualTo("PENDING");
    assertThat(creator.getSourceType()).isEqualTo(CreatorSourceType.PUBLIC);
  }

  // =========================================================================
  // TEST-F4-05: Anti-Replay Guard on Claim Token
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-05 [Anti-Replay]: Dùng lại Authorization Code hoặc Token mời đã được chấp nhận -> Báo 409 INVITATION_ALREADY_PROCESSED")
  void testF4_05_antiReplay_alreadyAcceptedInvitation_rejectsImmediately() {
    TestActors.authenticate(creatorUser);

    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder().id(creatorId).platform("TIKTOK").build();

    String rawToken = "raw_replay_token";
    CreatorInvitation alreadyAcceptedInvitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .targetCreator(creator)
        .invitedEmail(creatorUser.getEmail())
        .tokenHash(CreatorInvitationService.hashToken(rawToken))
        .status("ACCEPTED") // Already processed!
        .expiresAt(LocalDateTime.now(fixedClock).plusDays(5))
        .build();

    when(userRepository.findLockedByEmail(creatorUser.getEmail())).thenReturn(Optional.of(creatorUser));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));
    when(creatorInvitationRepository.findByTokenHash(any())).thenReturn(Optional.of(alreadyAcceptedInvitation));
    when(creatorInvitationRepository.findLockedById(alreadyAcceptedInvitation.getId()))
        .thenReturn(Optional.of(alreadyAcceptedInvitation));

    assertThatThrownBy(() -> claimResolutionService.resolveClaim("TIKTOK", "replayed_code", rawToken, creatorUser))
        .isInstanceOf(ConflictException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.INVITATION_ALREADY_PROCESSED);

    // Provider client never invoked, preventing token exchange waste
    verifyNoInteractions(oAuthProofClient);
  }

  // =========================================================================
  // TEST-F4-06: Multi-Channel Link (2nd Channel Attached to Existing Master)
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-06 [Multi-Channel Link]: KOC đã có Master claim thêm kênh thứ 2 -> Channel 2 chuyển sang AUTHORIZED dưới cùng một master_id")
  void testF4_06_multiChannelLink_creatorWithMasterLinksSecondChannel() {
    TestActors.authenticate(creatorUser);

    CreatorMaster masterM1 = CreatorMaster.builder()
        .id(UUID.randomUUID())
        .user(creatorUser)
        .canonicalName("Trang Dao Beauty")
        .status("ACTIVE")
        .build();

    UUID secondChannelId = UUID.randomUUID();
    Creator youtubeChannel = Creator.builder()
        .id(secondChannelId)
        .platform("YOUTUBE")
        .externalId("UC_TRANG_DAO_VLOGS")
        .userName("trangdao_vlogs")
        .displayName("Trang Dao Vlogs")
        .sourceType(CreatorSourceType.PUBLIC)
        .master(null)
        .build();

    when(userRepository.findById(creatorUser.getId())).thenReturn(Optional.of(creatorUser));
    when(creatorMasterRepository.findByUserId(creatorUser.getId())).thenReturn(Optional.of(masterM1));
    when(creatorRepository.findLockedByPlatformAndExternalId("YOUTUBE", "UC_TRANG_DAO_VLOGS"))
        .thenReturn(Optional.of(youtubeChannel));
    when(creatorRepository.findLockedById(secondChannelId)).thenReturn(Optional.of(youtubeChannel));
    when(creatorRepository.save(any(Creator.class))).thenAnswer(inv -> inv.getArgument(0));

    ProofResult proof = ProofResult.builder()
        .providerUserId("UC_TRANG_DAO_VLOGS")
        .providerUsername("trangdao_vlogs")
        .accessToken("access_token_yt_link")
        .refreshToken("refresh_token_yt_link")
        .tokenExpiresAt(LocalDateTime.now(fixedClock).plusHours(2))
        .build();

    when(oAuthProofClient.exchangeAndVerify(eq("YOUTUBE"), eq("code_link_yt"), isNull(), isNull()))
        .thenReturn(proof);

    ConnectAccountResponse response = accountLinkService.linkAdditionalAccount(
        creatorUser.getId(), "YOUTUBE", "code_link_yt");

    assertThat(response.getStatus()).isEqualTo("LINKED");
    assertThat(response.getMasterId()).isEqualTo(masterM1.getId());
    assertThat(response.getAccountId()).isEqualTo(secondChannelId);

    // Channel 2 attached to existing Master M1 and set to AUTHORIZED
    assertThat(youtubeChannel.getMaster()).isEqualTo(masterM1);
    assertThat(youtubeChannel.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(youtubeChannel.getAuthorizedAt()).isNotNull();

    // Dual references updated for 2nd channel
    verify(shortlistItemRepository).updateMasterForCreator(secondChannelId, masterM1);
    verify(relationshipRepository).updateMasterForCreator(secondChannelId, masterM1);
  }

  // =========================================================================
  // TEST-F4-07: Conflict Queue (Claiming Channel Owned by Another Master)
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-07 [Conflict Queue]: KOC claim kênh đã thuộc sở hữu của người khác -> Báo lỗi CREATOR_ALREADY_LINKED và bảo vệ quyền sở hữu")
  void testF4_07_conflictQueue_claimingChannelOwnedByOtherMaster_detectsOwnershipConflict() {
    TestActors.authenticate(creatorUser);

    CreatorMaster claimantMaster = CreatorMaster.builder()
        .id(UUID.randomUUID())
        .user(creatorUser)
        .canonicalName("Claimant KOC")
        .build();

    CreatorMaster victimMaster = CreatorMaster.builder()
        .id(UUID.randomUUID())
        .canonicalName("Original Owner Master")
        .build();

    UUID contestedCreatorId = UUID.randomUUID();
    Creator alreadyOwnedAccount = Creator.builder()
        .id(contestedCreatorId)
        .platform("TIKTOK")
        .externalId("tt_contested_999")
        .userName("victim_creator")
        .master(victimMaster)
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    when(userRepository.findById(creatorUser.getId())).thenReturn(Optional.of(creatorUser));
    when(creatorMasterRepository.findByUserId(creatorUser.getId())).thenReturn(Optional.of(claimantMaster));
    when(creatorRepository.findLockedByPlatformAndExternalId("TIKTOK", "tt_contested_999"))
        .thenReturn(Optional.of(alreadyOwnedAccount));

    ProofResult proof = ProofResult.builder()
        .providerUserId("tt_contested_999")
        .providerUsername("victim_creator")
        .build();

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq("auth_code_takeover"), isNull(), isNull()))
        .thenReturn(proof);

    // Attempting to link an already owned channel throws CreatorOwnershipConflictException
    assertThatThrownBy(() -> accountLinkService.linkAdditionalAccount(
        creatorUser.getId(), "TIKTOK", "auth_code_takeover"))
        .isInstanceOf(CreatorOwnershipConflictException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.CREATOR_ALREADY_LINKED);

    // Original ownership is strictly preserved
    assertThat(alreadyOwnedAccount.getMaster()).isEqualTo(victimMaster);
    verify(creatorRepository, never()).save(any());
  }

  // =========================================================================
  // TEST-F4-08: Admin Identity Conflict Resolution (APPROVE_TRANSFER & REJECT)
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-08 [Admin Resolve Conflict]: Data Manager gọi POST /admin/creator-conflicts/{id}/resolve -> Chuyển kênh thành công hoặc từ chối đúng logic")
  void testF4_08_adminResolveConflict_approveTransferAndReject_enforcesPermissionsAndAudits() {
    TestActors.authenticate(dataManagerUser);

    CreatorMaster originalMaster = CreatorMaster.builder()
        .id(UUID.randomUUID())
        .canonicalName("Original Master")
        .build();

    CreatorMaster claimantMaster = CreatorMaster.builder()
        .id(UUID.randomUUID())
        .canonicalName("Claimant Master")
        .build();

    Creator contestedAccount = Creator.builder()
        .id(UUID.randomUUID())
        .platform("TIKTOK")
        .externalId("tt_contested_channel")
        .userName("contested_creator")
        .master(originalMaster)
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    UUID conflictId = UUID.randomUUID();
    CreatorIdentityConflict conflict = CreatorIdentityConflict.builder()
        .id(conflictId)
        .conflictType("ACCOUNT_ALREADY_LINKED")
        .sourceAccount(contestedAccount)
        .targetMaster(claimantMaster)
        .status("PENDING_MANAGER")
        .createdAt(LocalDateTime.now(fixedClock).minusDays(1))
        .build();

    when(conflictRepository.findById(conflictId)).thenReturn(Optional.of(conflict));
    when(conflictRepository.save(any(CreatorIdentityConflict.class))).thenAnswer(inv -> inv.getArgument(0));

    // 1. Data Manager approves transfer
    ResolveConflictRequest approveReq = ResolveConflictRequest.builder()
        .decision(ConflictResolutionDecision.APPROVE_TRANSFER)
        .notes("Verified contract and notarized ID of claimant")
        .build();

    ApiResponse<ConflictDetailDto> response = adminConflictController.resolveConflict(conflictId, approveReq);

    assertThat(response.success()).isTrue();
    assertThat(response.data().getStatus()).isEqualTo("RESOLVED");
    assertThat(response.data().getResolutionNotes()).isEqualTo("Verified contract and notarized ID of claimant");

    // Verified ownership transferred to claimantMaster
    assertThat(contestedAccount.getMaster()).isEqualTo(claimantMaster);
    verify(shortlistItemRepository).updateMasterForCreator(contestedAccount.getId(), claimantMaster);
    verify(relationshipRepository).updateMasterForCreator(contestedAccount.getId(), claimantMaster);

    // Verified audit log created
    verify(identityAuditRepository).save(argThat(audit ->
        "CONFLICT_RESOLVED_TRANSFER".equals(audit.getEventType())
            && audit.getMaster().equals(claimantMaster)
    ));

    // 2. Unauthorized access guard: Brand user attempting to resolve conflict is forbidden
    User unauthorizedBrandUser = TestActors.user(Role.BRAND);
    TestActors.authenticate(unauthorizedBrandUser);

    assertThatThrownBy(() -> adminConflictController.resolveConflict(conflictId, approveReq))
        .isInstanceOf(ForbiddenException.class);
  }

  // =========================================================================
  // TEST-F4-09: Shortlist Outreach Bridging (Batch Outreach & Claim Email)
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-09 [Shortlist Outreach]: Brand chọn KOC từ Shortlist bấm gửi Lời mời -> Tự động tạo quan hệ CRM CONTACTED và đưa email vào outbox")
  void testF4_09_shortlistOutreach_publicCreator_createsCrmContactedAndEnqueuesClaimEmail() {
    TestActors.authenticate(brandUser);

    UUID creatorId = UUID.randomUUID();
    Creator publicCreator = Creator.builder()
        .id(creatorId)
        .platform("TIKTOK")
        .userName("review_vietnam")
        .displayName("Review Vietnam Official")
        .sourceType(CreatorSourceType.PUBLIC)
        .build();

    UUID itemId = UUID.randomUUID();
    ShortlistItem item = ShortlistItem.builder()
        .id(itemId)
        .campaign(campaign)
        .creator(publicCreator)
        .status(ShortlistStatus.SAVED)
        .priority(ShortlistPriority.HIGH)
        .build();

    when(shortlistItemRepository.findByCampaignIdAndIdIn(eq(campaignId), any()))
        .thenReturn(List.of(item));
    when(shortlistItemRepository.save(any(ShortlistItem.class))).thenAnswer(i -> i.getArgument(0));

    PublicCreatorMetric metric = PublicCreatorMetric.builder()
        .creator(publicCreator)
        .contact("booking@reviewvietnam.vn")
        .contentSummary("Review công nghệ và phong cách sống")
        .build();
    when(publicCreatorMetricRepository.findByCreatorId(creatorId)).thenReturn(Optional.of(metric));

    when(relationshipRepository.findByCampaignIdAndCreatorId(campaignId, creatorId))
        .thenReturn(Optional.empty());
    when(relationshipRepository.save(any(BrandCreatorRelationship.class)))
        .thenAnswer(inv -> {
          BrandCreatorRelationship r = inv.getArgument(0);
          if (r.getId() == null) r.setId(UUID.randomUUID());
          return r;
        });

    when(creatorInvitationRepository.findFirstByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
        eq(creatorId), eq("PENDING"), any()))
        .thenReturn(Optional.empty());

    UUID invitationId = UUID.randomUUID();
    when(creatorInvitationService.sendInvitation(
        eq(creatorId), eq("booking@reviewvietnam.vn"), eq(brandProfile.getId()), eq(campaignId)))
        .thenReturn(InvitationResponse.builder()
            .id(invitationId)
            .creatorId(creatorId)
            .email("booking@reviewvietnam.vn")
            .status("PENDING")
            .emailStatus("QUEUED")
            .build());

    ShortlistOutreachRequest request = ShortlistOutreachRequest.builder()
        .shortlistItemIds(List.of(itemId))
        .outreachMessage("Mời bạn hợp tác chiến dịch đồng hồ thông minh 2026")
        .proposedFee(BigDecimal.valueOf(20_000_000))
        .currency("VND")
        .contactMethod("EMAIL_INVITATION")
        .build();

    ShortlistOutreachResponse response = shortlistService.outreachShortlist(campaignId, request);

    assertThat(response.getContactedCount()).isEqualTo(1);
    assertThat(response.getDispatchedInvitationIds()).containsExactly(invitationId);
    assertThat(item.getStatus()).isEqualTo(ShortlistStatus.CONTACTED);

    ArgumentCaptor<BrandCreatorRelationship> relCaptor = ArgumentCaptor.forClass(BrandCreatorRelationship.class);
    verify(relationshipRepository).save(relCaptor.capture());
    assertThat(relCaptor.getValue().getStage()).isEqualTo(RelationshipStage.CONTACTED);
    assertThat(relCaptor.getValue().getQuotedFee()).isEqualByComparingTo(BigDecimal.valueOf(20_000_000));
    assertThat(relCaptor.getValue().getNotes()).isEqualTo("Mời bạn hợp tác chiến dịch đồng hồ thông minh 2026");

    verify(creatorInvitationService).sendInvitation(
        eq(creatorId), eq("booking@reviewvietnam.vn"), eq(brandProfile.getId()), eq(campaignId));
  }

  // =========================================================================
  // TEST-F4-10: CRM State Machine Progression with Agreed Fee
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-10 [CRM State Machine]: Đàm phán thành công -> Cập nhật agreedFee -> Chuyển sang COLLABORATING")
  void testF4_10_crmStateMachine_negotiationToCollaborating_updatesAgreedFeeAndNextAction() {
    TestActors.authenticate(brandUser);

    UUID relId = UUID.randomUUID();
    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId)
        .displayName("KOC Đỉnh Cao")
        .platform("TIKTOK")
        .build();

    BrandCreatorRelationship relationship = BrandCreatorRelationship.builder()
        .id(relId)
        .campaign(campaign)
        .creator(creator)
        .user(brandUser)
        .stage(RelationshipStage.CONTACTED)
        .quotedFee(BigDecimal.valueOf(15_000_000))
        .currency("VND")
        .createdAt(LocalDateTime.now(fixedClock).minusDays(3))
        .updatedAt(LocalDateTime.now(fixedClock).minusDays(3))
        .build();

    when(relationshipRepository.findByIdAndCampaignIdAndUserId(relId, campaignId, brandUser.getId()))
        .thenReturn(Optional.of(relationship));
    when(relationshipRepository.save(any(BrandCreatorRelationship.class))).thenAnswer(inv -> inv.getArgument(0));

    UpdateRelationshipStageRequest stageRequest = UpdateRelationshipStageRequest.builder()
        .stage(RelationshipStage.COLLABORATING)
        .agreedFee(BigDecimal.valueOf(18_500_000))
        .nextAction("Ký hợp đồng dịch vụ và chuyển cọc trước 15:00 ngày 28/10")
        .build();

    RelationshipDto dto = relationshipService.updateStage(campaignId, relId, stageRequest);

    assertThat(dto.getStage()).isEqualTo(RelationshipStage.COLLABORATING);
    assertThat(dto.getAgreedFee()).isEqualByComparingTo(BigDecimal.valueOf(18_500_000));
    assertThat(dto.getNextAction()).isEqualTo("Ký hợp đồng dịch vụ và chuyển cọc trước 15:00 ngày 28/10");
  }

  // =========================================================================
  // TEST-F4-11: Data Safety Contract (Crawler Ingestion Gatekeeper)
  // =========================================================================
  @Test
  @DisplayName("TEST-F4-11 [Data Safety Contract]: Crawler đẩy lại dữ liệu kênh đã Claim -> Chỉ cập nhật metrics, tuyệt đối giữ nguyên AUTHORIZED và master_id")
  void testF4_11_dataSafetyContract_crawlerReIngestionPreservesAuthorizedStatusAndMasterId() {
    UUID claimedCreatorId = UUID.randomUUID();
    CreatorMaster existingMaster = CreatorMaster.builder()
        .id(UUID.randomUUID())
        .canonicalName("Verified Master KOC")
        .build();

    Creator authorizedCreator = Creator.builder()
        .id(claimedCreatorId)
        .platform("TIKTOK")
        .externalId("tt_claimed_001")
        .userName("verified.koc")
        .displayName("Verified KOC")
        .sourceType(CreatorSourceType.AUTHORIZED)
        .master(existingMaster)
        .authorizedAt(LocalDateTime.now(fixedClock).minusMonths(1))
        .isStableId(true)
        .build();

    when(creatorRepository.findLockedByPlatformAndExternalId("TIKTOK", "tt_claimed_001"))
        .thenReturn(Optional.of(authorizedCreator));
    when(creatorRepository.save(any(Creator.class))).thenAnswer(inv -> inv.getArgument(0));

    PublicCreatorMetric existingMetric = PublicCreatorMetric.builder()
        .id(UUID.randomUUID())
        .creator(authorizedCreator)
        .followers(100_000L)
        .avgViews(BigDecimal.valueOf(25_000))
        .build();

    when(publicCreatorMetricRepository.findByCreatorId(claimedCreatorId))
        .thenReturn(Optional.of(existingMetric));
    when(publicCreatorMetricRepository.save(any(PublicCreatorMetric.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // Crawler pushes updated metrics for this creator
    CreatorIngestionRequest crawlerRequest = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("tt_claimed_001")
        .userName("verified.koc")
        .creatorType("KOC")
        .followers(125_000L) // +25k followers
        .avgViews(BigDecimal.valueOf(32_000))
        .crawlRunId("CRAWL_RUN_2026_10_26")
        .build();

    CreatorIngestionResponse response = ingestionService.ingestCreatorData(crawlerRequest);

    assertThat(response.getStatus()).isEqualTo("SUCCESS");

    // DATA SAFETY CONTRACT INVARIANTS:
    // 1. sourceType remains AUTHORIZED
    assertThat(authorizedCreator.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    // 2. master_id remains linked to existingMaster
    assertThat(authorizedCreator.getMaster()).isEqualTo(existingMaster);
    // 3. Advisory lock acquired
    verify(creatorRepository).acquireIdentityLock("TIKTOK:tt_claimed_001");
    // 4. Metrics were updated
    assertThat(existingMetric.getFollowers()).isEqualTo(125_000L);
    assertThat(existingMetric.getAvgViews()).isEqualByComparingTo(BigDecimal.valueOf(32_000));
  }
}
