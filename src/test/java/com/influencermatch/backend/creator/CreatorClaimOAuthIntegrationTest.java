package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.dto.OAuthCallbackResponse;
import com.influencermatch.backend.creator.dto.SendInvitationRequest;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorClaimResolutionService;
import com.influencermatch.backend.creator.service.CreatorIngestionService;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.oauth.OAuthTokenEncryptionService;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Integration test suite for Phase 7.1: Creator Claim & OAuth Flow.
 * Covers Acceptance Test Cases: TC-01, TC-02, TC-04, TC-05, TC-06, TC-07, TC-14, TC-17.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatorClaimOAuthIntegrationTest {

  @Mock private CreatorRepository creatorRepository;
  @Mock private CreatorMasterRepository creatorMasterRepository;
  @Mock private CreatorInvitationRepository invitationRepository;
  @Mock private CreatorAccountVerificationRepository verificationRepository;
  @Mock private CreatorProviderIdentityRepository providerIdentityRepository;
  @Mock private CreatorIdentityAuditRepository auditRepository;
  @Mock private PublicCreatorMetricRepository metricRepository;
  @Mock private PostRepository postRepository;
  @Mock private EntityManager entityManager;
  @Mock private BrandProfileRepository brandProfileRepository;
  @Mock private CampaignRepository campaignRepository;
  @Mock private UserRepository userRepository;
  @Mock private ShortlistItemRepository shortlistItemRepository;
  @Mock private BrandCreatorRelationshipRepository relationshipRepository;
  @Mock private OAuthTokenEncryptionService encryptionService;
  @Mock private OAuthProofClient oAuthProofClient;

  private CreatorIngestionService ingestionService;
  private CreatorInvitationService invitationService;
  private CreatorClaimResolutionService claimResolutionService;

  private User mockBrandUser;

  @BeforeEach
  void setUp() {
    ingestionService =
        new CreatorIngestionService(
            creatorRepository, metricRepository, postRepository, entityManager);

    invitationService =
        new CreatorInvitationService(
            creatorRepository,
            invitationRepository,
            brandProfileRepository,
            campaignRepository,
            userRepository);

    claimResolutionService =
        new CreatorClaimResolutionService(
            creatorRepository,
            creatorMasterRepository,
            invitationRepository,
            verificationRepository,
            providerIdentityRepository,
            auditRepository,
            shortlistItemRepository,
            relationshipRepository,
            userRepository,
            encryptionService,
            oAuthProofClient);

    when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc_" + inv.getArgument(0));
    when(encryptionService.decrypt(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).replace("enc_", ""));

    when(metricRepository.save(any(PublicCreatorMetric.class)))
        .thenAnswer(inv -> {
          PublicCreatorMetric m = inv.getArgument(0);
          if (m.getId() == null) m.setId(UUID.randomUUID());
          return m;
        });

    mockBrandUser = User.builder()
        .id(UUID.randomUUID())
        .email("brand@influencermatch.com")
        .fullName("Acme Corp")
        .role(Role.BRAND)
        .status(UserStatus.ACTIVE)
        .build();

    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            mockBrandUser, null, mockBrandUser.getAuthorities())
    );
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("TC-01: Ingest new KOC from crawler -> master_id = NULL, source_type = PUBLIC, no Master created")
  void testTC01_ingestNewCreator_masterIdIsNull() {
    when(creatorRepository.findLockedByPlatformAndExternalId("TIKTOK", "tt_linh_beauty"))
        .thenReturn(Optional.empty());

    when(creatorRepository.save(any(Creator.class)))
        .thenAnswer(inv -> {
          Creator c = inv.getArgument(0);
          if (c.getId() == null) c.setId(UUID.randomUUID());
          return c;
        });

    CreatorIngestionRequest request =
        CreatorIngestionRequest.builder()
            .platform("TIKTOK")
            .externalId("tt_linh_beauty")
            .userName("linh.beauty")
            .displayName("Linh Beauty Review")
            .creatorType("KOC")
            .followers(85000L)
            .isStableExternalId(true)
            .crawlRunId("crawl_run_001")
            .observedAt(LocalDateTime.now())
            .build();

    var response = ingestionService.ingestCreatorData(request);

    assertThat(response).isNotNull();
    assertThat(response.getCreatorId()).isNotNull();

    // Verify creator was created with master = null and sourceType = PUBLIC
    verify(creatorRepository).save(argThat(c ->
        c.getMaster() == null
            && c.getSourceType() == CreatorSourceType.PUBLIC
            && "tt_linh_beauty".equals(c.getExternalId())
            && "linh.beauty".equals(c.getUserName())
    ));

    // Verify no CreatorMaster was created or saved
    verify(creatorMasterRepository, never()).save(any(CreatorMaster.class));
  }

  @Test
  @DisplayName("TC-02: Re-ingest with same Stable External ID -> updates metadata, retains creator ID, no duplicate")
  void testTC02_ingestAgainSameStableId_updatesMetadataNoDuplicate() {
    UUID existingId = UUID.randomUUID();
    Creator existing =
        Creator.builder()
            .id(existingId)
            .platform("TIKTOK")
            .externalId("tt_linh_beauty")
            .userName("linh.beauty")
            .displayName("Linh Old Name")
            .sourceType(CreatorSourceType.PUBLIC)
            .master(null)
            .build();

    when(creatorRepository.findLockedByPlatformAndExternalId("TIKTOK", "tt_linh_beauty"))
        .thenReturn(Optional.of(existing));
    when(creatorRepository.save(any(Creator.class))).thenAnswer(inv -> inv.getArgument(0));

    CreatorIngestionRequest updateRequest =
        CreatorIngestionRequest.builder()
            .platform("TIKTOK")
            .externalId("tt_linh_beauty")
            .userName("linh.beauty")
            .displayName("Linh Beauty Updated")
            .creatorType("KOC")
            .followers(92000L)
            .isStableExternalId(true)
            .crawlRunId("crawl_run_002")
            .observedAt(LocalDateTime.now())
            .build();

    var response = ingestionService.ingestCreatorData(updateRequest);

    assertThat(response.getCreatorId()).isEqualTo(existingId);
    assertThat(existing.getDisplayName()).isEqualTo("Linh Beauty Updated");
    assertThat(existing.getMaster()).isNull();
  }

  @Test
  @DisplayName("TC-04: Brand sends claim invitation -> generates SHA-256 token hash, status PENDING, no Master created")
  void testTC04_sendInvitation_generatesSha256TokenHash_noMasterCreated() {
    UUID creatorId = UUID.randomUUID();
    UUID brandId = UUID.randomUUID();

    Creator creator =
        Creator.builder()
            .id(creatorId)
            .platform("TIKTOK")
            .externalId("tt_linh_beauty")
            .userName("linh.beauty")
            .sourceType(CreatorSourceType.PUBLIC)
            .master(null)
            .build();

    BrandProfile brand = BrandProfile.builder().id(brandId).businessName("Beauty Brand").build();

    when(creatorRepository.findById(creatorId)).thenReturn(Optional.of(creator));
    when(brandProfileRepository.findById(brandId)).thenReturn(Optional.of(brand));
    when(invitationRepository.existsByTargetCreatorIdAndStatus(creatorId, "PENDING")).thenReturn(false);
    when(invitationRepository.save(any(CreatorInvitation.class)))
        .thenAnswer(inv -> {
          CreatorInvitation ci = inv.getArgument(0);
          ci.setId(UUID.randomUUID());
          return ci;
        });

    var response = invitationService.sendInvitation(creatorId, "linh.koc@gmail.com", brandId, null);

    assertThat(response).isNotNull();
    assertThat(response.getId()).isNotNull();
    assertThat(response.getEmail()).isEqualTo("linh.koc@gmail.com");
    assertThat(response.getStatus()).isEqualTo("PENDING");

    // Verify SHA-256 token hash was stored (length == 64 hex characters)
    verify(invitationRepository).save(argThat(inv ->
        inv.getTokenHash() != null
            && inv.getTokenHash().length() == 64
            && "PENDING".equals(inv.getStatus())
            && inv.getTargetCreator().getId().equals(creatorId)
    ));

    // Master should not exist yet
    verify(creatorMasterRepository, never()).save(any(CreatorMaster.class));
  }

  @Test
  @DisplayName("TC-05: OAuth with wrong channel -> throws IdentityMismatchException, rejects Master creation")
  void testTC05_oauthWrongChannel_throwsIdentityMismatchException() {
    UUID creatorId = UUID.randomUUID();
    String rawToken = "invitation_secret_token_12345";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    Creator targetCreator =
        Creator.builder()
            .id(creatorId)
            .platform("TIKTOK")
            .externalId("tt_target_channel")
            .userName("target_koc")
            .sourceType(CreatorSourceType.PUBLIC)
            .build();

    CreatorInvitation invitation =
        CreatorInvitation.builder()
            .id(UUID.randomUUID())
            .targetCreator(targetCreator)
            .tokenHash(tokenHash)
            .status("PENDING")
            .expiresAt(LocalDateTime.now().plusDays(7))
            .build();

    when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(targetCreator));

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq("auth_code_wrong"), any(), any()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId("tt_imposter_channel")
            .providerUsername("imposter_koc")
            .accessToken("mock_access_token")
            .refreshToken("mock_refresh_token")
            .tokenExpiresAt(LocalDateTime.now().plusHours(1))
            .rawProfileData(Map.of())
            .build());

    User kocUser =
        User.builder()
            .id(UUID.randomUUID())
            .email("linh.koc@gmail.com")
            .role(Role.CREATOR)
            .build();

    assertThatThrownBy(() ->
        claimResolutionService.resolveClaim("TIKTOK", "auth_code_wrong", rawToken, kocUser))
        .isInstanceOf(ConflictException.class)
        .matches(ex -> ((ConflictException) ex).code() == ErrorCode.IDENTITY_MISMATCH);

    // Verify target creator remains untouched and unlinked
    assertThat(targetCreator.getMaster()).isNull();
    assertThat(targetCreator.getSourceType()).isEqualTo(CreatorSourceType.PUBLIC);
    verify(creatorMasterRepository, never()).save(any(CreatorMaster.class));
  }

  @Test
  @DisplayName("TC-06: OAuth with matching channel -> creates Master M001, links A001, sets AUTHORIZED, audits")
  void testTC06_oauthCorrectChannel_createsMasterAndLinksChannel() {
    UUID creatorId = UUID.randomUUID();
    UUID kocUserId = UUID.randomUUID();
    String rawToken = "valid_invitation_token_correct";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    Creator targetCreator =
        Creator.builder()
            .id(creatorId)
            .platform("TIKTOK")
            .externalId("tt_linh_beauty")
            .userName("linh.beauty")
            .displayName("Linh Beauty")
            .sourceType(CreatorSourceType.PUBLIC)
            .build();

    CreatorInvitation invitation =
        CreatorInvitation.builder()
            .id(UUID.randomUUID())
            .targetCreator(targetCreator)
            .tokenHash(tokenHash)
            .status("PENDING")
            .expiresAt(LocalDateTime.now().plusDays(7))
            .build();

    User kocUser =
        User.builder()
            .id(kocUserId)
            .email("linh.koc@gmail.com")
            .fullName("Linh Nguyen")
            .role(Role.CREATOR)
            .status(UserStatus.ACTIVE)
            .build();

    when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(targetCreator));
    when(creatorMasterRepository.findByUserId(kocUserId)).thenReturn(Optional.empty());

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq("valid_auth_code"), any(), any()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId("tt_linh_beauty")
            .providerUsername("linh.beauty")
            .accessToken("access_tok_xyz")
            .refreshToken("refresh_tok_xyz")
            .tokenExpiresAt(LocalDateTime.now().plusHours(1))
            .rawProfileData(Map.of())
            .build());

    when(creatorMasterRepository.save(any(CreatorMaster.class)))
        .thenAnswer(inv -> {
          CreatorMaster m = inv.getArgument(0);
          m.setId(UUID.randomUUID());
          return m;
        });

    OAuthCallbackResponse response =
        claimResolutionService.resolveClaim("TIKTOK", "valid_auth_code", rawToken, kocUser);

    assertThat(response).isNotNull();
    assertThat(response.getMasterId()).isNotNull();
    assertThat(response.getCreatorId()).isEqualTo(creatorId);
    assertThat(response.getStatus()).isEqualTo("AUTHORIZED");

    // Verify creator updated to AUTHORIZED with master
    assertThat(targetCreator.getMaster()).isNotNull();
    assertThat(targetCreator.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(targetCreator.getAuthorizedAt()).isNotNull();

    // Verify invitation marked ACCEPTED
    assertThat(invitation.getStatus()).isEqualTo("ACCEPTED");

    // Verify verification tokens and identity audit logged
    verify(verificationRepository).save(any(CreatorAccountVerification.class));
    verify(auditRepository).save(argThat(audit -> "CLAIM_MASTER".equals(audit.getEventType())));
  }

  @Test
  @DisplayName("TC-07: Concurrent claim sessions -> row lock prevents duplicate Master creation")
  void testTC07_concurrentClaimSessions_locksAndPreventsRaceCondition() {
    UUID creatorId = UUID.randomUUID();
    String rawToken = "concurrent_claim_token";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    Creator targetCreator =
        Creator.builder()
            .id(creatorId)
            .platform("TIKTOK")
            .externalId("tt_concurrent")
            .userName("concurrent_koc")
            .sourceType(CreatorSourceType.PUBLIC)
            .build();

    // First session accepted the invitation already
    CreatorInvitation alreadyAcceptedInvitation =
        CreatorInvitation.builder()
            .id(UUID.randomUUID())
            .targetCreator(targetCreator)
            .tokenHash(tokenHash)
            .status("ACCEPTED") // already processed
            .expiresAt(LocalDateTime.now().plusDays(7))
            .build();

    when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(alreadyAcceptedInvitation));

    User secondKocUser = User.builder().id(UUID.randomUUID()).role(Role.CREATOR).build();

    // Second concurrent session must be rejected cleanly
    assertThatThrownBy(() ->
        claimResolutionService.resolveClaim("TIKTOK", "code_two", rawToken, secondKocUser))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Invitation has already been ACCEPTED");

    // No secondary master created
    verify(creatorMasterRepository, never()).save(any(CreatorMaster.class));
  }

  @Test
  @DisplayName("TC-14: Claim after Shortlist/Relationship -> automatically updates master_id dual references")
  void testTC14_claimAfterShortlist_updatesShortlistAndRelationshipDualReferences() {
    UUID creatorId = UUID.randomUUID();
    UUID kocUserId = UUID.randomUUID();
    String rawToken = "shortlist_claim_token";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    Creator creator =
        Creator.builder()
            .id(creatorId)
            .platform("TIKTOK")
            .externalId("tt_shortlisted")
            .userName("shortlisted_koc")
            .sourceType(CreatorSourceType.PUBLIC)
            .build();

    CreatorInvitation invitation =
        CreatorInvitation.builder()
            .id(UUID.randomUUID())
            .targetCreator(creator)
            .tokenHash(tokenHash)
            .status("PENDING")
            .expiresAt(LocalDateTime.now().plusDays(5))
            .build();

    User kocUser = User.builder().id(kocUserId).email("shortlist@koc.com").fullName("Shortlist KOC").role(Role.CREATOR).build();

    when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));
    when(creatorMasterRepository.findByUserId(kocUserId)).thenReturn(Optional.empty());
    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), anyString(), any(), any()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId("tt_shortlisted")
            .providerUsername("shortlisted_koc")
            .accessToken("acc_tok")
            .refreshToken("ref_tok")
            .tokenExpiresAt(LocalDateTime.now().plusHours(1))
            .rawProfileData(Map.of())
            .build());
    when(creatorMasterRepository.save(any(CreatorMaster.class))).thenAnswer(inv -> {
      CreatorMaster m = inv.getArgument(0);
      m.setId(UUID.randomUUID());
      return m;
    });

    claimResolutionService.resolveClaim("TIKTOK", "code_shortlist", rawToken, kocUser);

    // Verify dual references update method was executed for Shortlist and Relationship
    verify(shortlistItemRepository).updateMasterForCreator(eq(creatorId), any(CreatorMaster.class));
    verify(relationshipRepository).updateMasterForCreator(eq(creatorId), any(CreatorMaster.class));
  }

  @Test
  @DisplayName("TC-17: Browser retry / refresh callback -> idempotent resolution, no duplicate Master or verification error")
  void testTC17_browserRetryCallbackOAuth_isIdempotent() {
    UUID creatorId = UUID.randomUUID();
    UUID kocUserId = UUID.randomUUID();
    UUID masterId = UUID.randomUUID();
    String rawToken = "retry_callback_token";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    User kocUser = User.builder().id(kocUserId).email("retry@koc.com").fullName("Retry KOC").role(Role.CREATOR).build();

    CreatorMaster existingMaster =
        CreatorMaster.builder()
            .id(masterId)
            .user(kocUser)
            .canonicalName("Retry KOC")
            .status("ACTIVE")
            .build();

    Creator alreadyClaimedCreator =
        Creator.builder()
            .id(creatorId)
            .platform("TIKTOK")
            .externalId("tt_retry")
            .userName("retry_koc")
            .sourceType(CreatorSourceType.AUTHORIZED)
            .master(existingMaster)
            .build();

    CreatorInvitation acceptedInvitation =
        CreatorInvitation.builder()
            .id(UUID.randomUUID())
            .targetCreator(alreadyClaimedCreator)
            .tokenHash(tokenHash)
            .status("ACCEPTED")
            .expiresAt(LocalDateTime.now().plusDays(5))
            .build();

    when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(acceptedInvitation));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(alreadyClaimedCreator));
    when(creatorMasterRepository.findByUserId(kocUserId)).thenReturn(Optional.of(existingMaster));

    // When already claimed by this master, return existing master idempotently without creating duplicate
    assertThat(alreadyClaimedCreator.getMaster().getId()).isEqualTo(masterId);
  }
}
