package com.influencermatch.backend.creator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.creator.client.OAuthProofClient.ProofResult;
import com.influencermatch.backend.creator.dto.OAuthCallbackResponse;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorClaimResolutionService;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.oauth.OAuthTokenEncryptionService;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CreatorClaimOAuthPhase2Test {

  private CreatorClaimResolutionService claimResolutionService;
  private CreatorInvitationService invitationService;
  private OAuthProofClient proofClient;
  private CreatorClaimOAuthController controller;

  // Resolution Service dependencies
  private CreatorRepository creatorRepository;
  private CreatorMasterRepository creatorMasterRepository;
  private CreatorInvitationRepository creatorInvitationRepository;
  private CreatorAccountVerificationRepository accountVerificationRepository;
  private CreatorProviderIdentityRepository providerIdentityRepository;
  private CreatorIdentityAuditRepository identityAuditRepository;
  private ShortlistItemRepository shortlistItemRepository;
  private BrandCreatorRelationshipRepository relationshipRepository;
  private UserRepository userRepository;
  private OAuthTokenEncryptionService tokenEncryptionService;
  private EntityManager entityManager;

  private User testCreatorUser;
  private Creator testCreator;
  private CreatorInvitation testInvitation;

  @BeforeEach
  void setUp() {
    testCreatorUser = User.builder()
        .id(UUID.randomUUID())
        .email("koc.authentic@tiktok.test")
        .fullName("Trang Dao Beauty")
        .role(Role.CREATOR)
        .status(UserStatus.ACTIVE)
        .build();

    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(testCreatorUser, null, testCreatorUser.getAuthorities()));

    creatorRepository = mock(CreatorRepository.class);
    creatorMasterRepository = mock(CreatorMasterRepository.class);
    creatorInvitationRepository = mock(CreatorInvitationRepository.class);
    accountVerificationRepository = mock(CreatorAccountVerificationRepository.class);
    providerIdentityRepository = mock(CreatorProviderIdentityRepository.class);
    identityAuditRepository = mock(CreatorIdentityAuditRepository.class);
    shortlistItemRepository = mock(ShortlistItemRepository.class);
    relationshipRepository = mock(BrandCreatorRelationshipRepository.class);
    userRepository = mock(UserRepository.class);
    tokenEncryptionService = mock(OAuthTokenEncryptionService.class);
    proofClient = mock(OAuthProofClient.class);
    invitationService = mock(CreatorInvitationService.class);
    entityManager = mock(EntityManager.class);

    Clock fixedClock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneId.of("UTC"));

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
        proofClient);

    com.influencermatch.backend.support.CreatorServiceFixtures.wire(claimResolutionService);
    org.springframework.test.util.ReflectionTestUtils.setField(claimResolutionService, "clock", fixedClock);
    org.springframework.test.util.ReflectionTestUtils.setField(claimResolutionService, "em", entityManager);

    controller = new CreatorClaimOAuthController(claimResolutionService, invitationService, proofClient);

    // Default test entities
    testCreator = Creator.builder()
        .id(UUID.randomUUID())
        .platform("TIKTOK")
        .externalId("tt_trangdao_001")
        .userName("@trangdao.beauty")
        .displayName("Trang Dao Beauty")
        .sourceType(CreatorSourceType.PUBLIC)
        .isStableId(true)
        .build();

    testInvitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .targetCreator(testCreator)
        .invitedEmail("koc.authentic@tiktok.test")
        .tokenHash(CreatorInvitationService.hashToken("raw_invitation_token_123"))
        .status("PENDING")
        .expiresAt(LocalDateTime.now(fixedClock).plusDays(5))
        .build();

    when(userRepository.findLockedByEmail(testCreatorUser.getEmail())).thenReturn(Optional.of(testCreatorUser));
    when(creatorRepository.findLockedById(testCreator.getId())).thenReturn(Optional.of(testCreator));
    when(creatorInvitationRepository.findByTokenHash(any())).thenReturn(Optional.of(testInvitation));
    when(creatorInvitationRepository.findLockedById(testInvitation.getId())).thenReturn(Optional.of(testInvitation));
    when(tokenEncryptionService.encrypt(any())).thenAnswer(inv -> "enc_" + inv.getArgument(0));
    when(creatorMasterRepository.findByUserId(testCreatorUser.getId())).thenReturn(Optional.empty());
    when(creatorMasterRepository.save(any(CreatorMaster.class))).thenAnswer(inv -> {
      CreatorMaster m = inv.getArgument(0);
      if (m.getId() == null) m.setId(UUID.randomUUID());
      return m;
    });
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("startOAuth redirects to Official TikTok Developer Sandbox consent screen URL")
  void startOAuthRedirectsToOfficialSandbox() {
    String rawToken = "raw_invitation_token_123";
    String expectedSandboxUrl = "https://www.tiktok.com/v2/auth/authorize/?client_key=mock_key&redirect_uri=mock_redirect&response_type=code&scope=user.info.basic&state=" + rawToken;

    when(proofClient.buildAuthorizationUrl("tiktok", rawToken)).thenReturn(expectedSandboxUrl);

    ResponseEntity<Void> response = controller.startOAuth("tiktok", rawToken, true);

    verify(proofClient).requireConfigured();
    verify(invitationService).verifyInvitationToken(rawToken);
    verify(proofClient).buildAuthorizationUrl("tiktok", rawToken);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(expectedSandboxUrl));
  }

  @Test
  @DisplayName("resolveClaim succeeds with Official TikTok Sandbox and records OAUTH_OFFICIAL_SANDBOX")
  void resolveClaimSucceedsWithOfficialSandbox() {
    String rawToken = "raw_invitation_token_123";
    String authCode = "official_sandbox_auth_code_999";

    ProofResult proof = ProofResult.builder()
        .providerUserId("open_id_trangdao_sandbox")
        .providerUsername("trangdao.beauty")
        .accessToken("access_token_tiktok_sandbox")
        .refreshToken("refresh_token_tiktok_sandbox")
        .tokenExpiresAt(LocalDateTime.now().plusDays(1))
        .rawProfileData(Map.of(
            "platform", "TIKTOK",
            "verificationEnvironment", "OFFICIAL_SANDBOX",
            "username", "trangdao.beauty"
        ))
        .build();

    when(proofClient.exchangeAndVerify(eq("TIKTOK"), eq(authCode), eq(testCreator.getExternalId()), eq(testCreator.getUserName())))
        .thenReturn(proof);

    OAuthCallbackResponse result = claimResolutionService.resolveClaim("TIKTOK", authCode, rawToken, testCreatorUser);

    assertThat(result).isNotNull();
    assertThat(result.getStatus()).isEqualTo("AUTHORIZED");
    assertThat(result.getCreatorId()).isEqualTo(testCreator.getId());

    // Verify Creator ownership set to AUTHORIZED
    assertThat(testCreator.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(testCreator.getMaster()).isNotNull();

    // Verify verificationMethod is OAUTH_OFFICIAL_SANDBOX
    ArgumentCaptor<CreatorAccountVerification> verificationCaptor = ArgumentCaptor.forClass(CreatorAccountVerification.class);
    verify(accountVerificationRepository).save(verificationCaptor.capture());
    assertThat(verificationCaptor.getValue().getVerificationMethod()).isEqualTo("OAUTH_OFFICIAL_SANDBOX");

    // Verify Audit log records verificationMethod
    ArgumentCaptor<CreatorIdentityAudit> auditCaptor = ArgumentCaptor.forClass(CreatorIdentityAudit.class);
    verify(identityAuditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getDetails().get("verificationMethod")).isEqualTo("OAUTH_OFFICIAL_SANDBOX");
  }

  @Test
  @DisplayName("resolveClaim succeeds with Google YouTube Testing Mode and records OAUTH_TESTING_GOOGLE")
  void resolveClaimSucceedsWithGoogleYouTubeTesting() {
    testCreator.setPlatform("YOUTUBE");
    testCreator.setExternalId("UC_CHAU_BUI_OFFICIAL");
    testCreator.setUserName("Chau Bui");
    testInvitation.getTargetCreator().setPlatform("YOUTUBE");

    String rawToken = "raw_invitation_token_123";
    String authCode = "google_testing_auth_code_888";

    ProofResult proof = ProofResult.builder()
        .providerUserId("UC_CHAU_BUI_OFFICIAL")
        .providerUsername("Chau Bui Official")
        .accessToken("access_token_google_testing")
        .refreshToken("refresh_token_google_testing")
        .tokenExpiresAt(LocalDateTime.now().plusHours(2))
        .rawProfileData(Map.of(
            "platform", "YOUTUBE",
            "verificationEnvironment", "TESTING_MODE",
            "channelId", "UC_CHAU_BUI_OFFICIAL"
        ))
        .build();

    when(proofClient.exchangeAndVerify(eq("YOUTUBE"), eq(authCode), eq("UC_CHAU_BUI_OFFICIAL"), eq("Chau Bui")))
        .thenReturn(proof);

    OAuthCallbackResponse result = claimResolutionService.resolveClaim("YOUTUBE", authCode, rawToken, testCreatorUser);

    assertThat(result).isNotNull();
    assertThat(result.getStatus()).isEqualTo("AUTHORIZED");

    ArgumentCaptor<CreatorAccountVerification> verificationCaptor = ArgumentCaptor.forClass(CreatorAccountVerification.class);
    verify(accountVerificationRepository).save(verificationCaptor.capture());
    assertThat(verificationCaptor.getValue().getVerificationMethod()).isEqualTo("OAUTH_TESTING_GOOGLE");
  }

  @Test
  @DisplayName("resolveClaim rejects identity mismatch with HTTP 409 IDENTITY_MISMATCH")
  void resolveClaimRejectsIdentityMismatch() {
    String rawToken = "raw_invitation_token_123";
    String authCode = "auth_code_wrong_account";

    ProofResult proof = ProofResult.builder()
        .providerUserId("imposter_id")
        .providerUsername("imposter_user")
        .accessToken("access_token_imposter")
        .tokenExpiresAt(LocalDateTime.now().plusHours(1))
        .rawProfileData(Map.of("platform", "TIKTOK"))
        .build();

    when(proofClient.exchangeAndVerify(eq("TIKTOK"), eq(authCode), eq(testCreator.getExternalId()), eq(testCreator.getUserName())))
        .thenReturn(proof);

    assertThatThrownBy(() -> claimResolutionService.resolveClaim("TIKTOK", authCode, rawToken, testCreatorUser))
        .isInstanceOf(ConflictException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.IDENTITY_MISMATCH);

    verify(creatorMasterRepository, never()).save(any());
    verify(accountVerificationRepository, never()).save(any());
  }

  @Test
  @DisplayName("resolveClaim blocks replay attack when invitation is already ACCEPTED")
  void resolveClaimBlocksReplayAttack() {
    testInvitation.setStatus("ACCEPTED");
    String rawToken = "raw_invitation_token_123";

    assertThatThrownBy(() -> claimResolutionService.resolveClaim("TIKTOK", "code", rawToken, testCreatorUser))
        .isInstanceOf(ConflictException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.INVITATION_ALREADY_PROCESSED);

    verifyNoInteractions(proofClient);
  }
}
