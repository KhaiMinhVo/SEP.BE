package com.influencermatch.backend.creator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.creator.dto.OAuthCallbackResponse;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.oauth.OAuthTokenEncryptionService;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CreatorClaimResolutionServiceTest {

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
  private OAuthProofClient oAuthProofClient;

  private CreatorClaimResolutionService resolutionService;

  @BeforeEach
  void setUp() {
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
    oAuthProofClient = mock(OAuthProofClient.class);

    resolutionService = new CreatorClaimResolutionService(
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
        oAuthProofClient
    );
  }

  @Test
  void resolveClaim_success_flow() {
    String rawToken = "invitation_token_123";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId)
        .platform("YOUTUBE")
        .externalId("UC_GENUINE_123")
        .userName("tech_pro")
        .displayName("Tech Pro")
        .sourceType(CreatorSourceType.PUBLIC)
        .master(null)
        .build();

    CreatorInvitation invitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .targetCreator(creator)
        .tokenHash(tokenHash)
        .invitedEmail("koc@influencermatch.com")
        .status("PENDING")
        .expiresAt(LocalDateTime.now().plusDays(5))
        .build();

    User claimantUser = User.builder()
        .id(UUID.randomUUID())
        .email("koc@influencermatch.com")
        .fullName("Tech Pro Influencer")
        .role(Role.CREATOR)
        .status(UserStatus.ACTIVE)
        .build();

    when(creatorInvitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));
    when(userRepository.findByEmail("koc@influencermatch.com")).thenReturn(Optional.of(claimantUser));

    // Proof client matches external id
    when(oAuthProofClient.exchangeAndVerify(eq("YOUTUBE"), eq("auth_code_xyz"), eq("UC_GENUINE_123"), eq("tech_pro")))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId("UC_GENUINE_123")
            .providerUsername("tech_pro")
            .accessToken("raw_access_token")
            .refreshToken("raw_refresh_token")
            .tokenExpiresAt(LocalDateTime.now().plusHours(2))
            .rawProfileData(Map.of("channelTitle", "Tech Pro"))
            .build());

    when(tokenEncryptionService.encrypt("raw_access_token")).thenReturn("enc_access_token");
    when(tokenEncryptionService.encrypt("raw_refresh_token")).thenReturn("enc_refresh_token");

    when(creatorMasterRepository.findByUserId(claimantUser.getId())).thenReturn(Optional.empty());
    when(creatorMasterRepository.save(any())).thenAnswer(i -> {
      CreatorMaster m = i.getArgument(0);
      m.setId(UUID.randomUUID());
      return m;
    });

    when(creatorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    OAuthCallbackResponse response = resolutionService.resolveClaim(
        "YOUTUBE", "auth_code_xyz", rawToken, claimantUser);

    assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
    assertThat(response.getCreatorId()).isEqualTo(creatorId);
    assertThat(response.getMasterId()).isNotNull();

    // Verify creator updated to AUTHORIZED and linked to master
    assertThat(creator.getMaster()).isNotNull();
    assertThat(creator.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(creator.getAuthorizedAt()).isNotNull();

    // Verify verification record
    ArgumentCaptor<CreatorAccountVerification> verCaptor =
        ArgumentCaptor.forClass(CreatorAccountVerification.class);
    verify(accountVerificationRepository).save(verCaptor.capture());
    CreatorAccountVerification ver = verCaptor.getValue();
    assertThat(ver.getAccessTokenEncrypted()).isEqualTo("enc_access_token");
    assertThat(ver.getRefreshTokenEncrypted()).isEqualTo("enc_refresh_token");
    assertThat(ver.getStatus()).isEqualTo("ACTIVE");

    // Verify provider identity
    verify(providerIdentityRepository).save(any());

    // Verify invitation accepted
    assertThat(invitation.getStatus()).isEqualTo("ACCEPTED");
    verify(creatorInvitationRepository).save(invitation);

    // Verify audit log
    verify(identityAuditRepository).save(any());

    // Verify dual reference updates
    verify(shortlistItemRepository).updateMasterForCreator(eq(creatorId), any());
    verify(relationshipRepository).updateMasterForCreator(eq(creatorId), any());
  }

  @Test
  void resolveClaim_identityMismatch_throwsConflict() {
    String rawToken = "invitation_token_mismatch";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId)
        .platform("YOUTUBE")
        .externalId("UC_TARGET_ID")
        .userName("channel_a")
        .sourceType(CreatorSourceType.PUBLIC)
        .build();

    CreatorInvitation invitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .targetCreator(creator)
        .tokenHash(tokenHash)
        .status("PENDING")
        .expiresAt(LocalDateTime.now().plusDays(5))
        .build();

    when(creatorInvitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));

    // Proof client returns DIFFERENT channel id
    when(oAuthProofClient.exchangeAndVerify(any(), any(), any(), any()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId("UC_DIFFERENT_CHANNEL_HACKER")
            .providerUsername("different_user")
            .build());

    assertThatThrownBy(() -> resolutionService.resolveClaim(
        "YOUTUBE", "code_fake", rawToken, null))
        .isInstanceOf(ConflictException.class)
        .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo(ErrorCode.IDENTITY_MISMATCH));

    verify(creatorMasterRepository, never()).save(any());
  }

  @Test
  void resolveClaim_alreadyClaimed_throwsConflict() {
    String rawToken = "invitation_token_claimed";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId)
        .platform("TIKTOK")
        .externalId("tt_123")
        .userName("tt_koc")
        .master(CreatorMaster.builder().id(UUID.randomUUID()).build())
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    CreatorInvitation invitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .targetCreator(creator)
        .tokenHash(tokenHash)
        .status("PENDING")
        .expiresAt(LocalDateTime.now().plusDays(5))
        .build();

    when(creatorInvitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));

    assertThatThrownBy(() -> resolutionService.resolveClaim(
        "TIKTOK", "code_123", rawToken, null))
        .isInstanceOf(ConflictException.class)
        .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo(ErrorCode.CREATOR_ALREADY_LINKED));

    verify(oAuthProofClient, never()).exchangeAndVerify(any(), any(), any(), any());
  }

  @Test
  void claimNeverCreatesUserOrBypassesEmailVerification() {
    var creator=Creator.builder().id(UUID.randomUUID()).platform("TIKTOK")
        .externalId("account").userName("creator").build();
    var invitation=CreatorInvitation.builder().id(UUID.randomUUID()).targetCreator(creator)
        .invitedEmail("pending@example.test").status("PENDING")
        .expiresAt(LocalDateTime.now().plusDays(1)).build();
    when(creatorInvitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));
    when(creatorRepository.findLockedById(creator.getId())).thenReturn(Optional.of(creator));
    when(oAuthProofClient.exchangeAndVerify(any(),any(),any(),any())).thenReturn(
        OAuthProofClient.ProofResult.builder().providerUserId("account").providerUsername("creator").build());
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> resolutionService.resolveClaim("TIKTOK","code","invite",null))
        .isInstanceOfSatisfying(com.influencermatch.backend.exception.BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    var pending=User.builder().id(UUID.randomUUID()).email("pending@example.test")
        .role(Role.CREATOR).status(UserStatus.ACTIVE).emailVerified(false).build();
    assertThatThrownBy(() -> resolutionService.resolveClaim("TIKTOK","code","invite",pending))
        .isInstanceOf(com.influencermatch.backend.exception.ForbiddenException.class);
    verify(userRepository,never()).save(any());
    verify(creatorMasterRepository,never()).save(any());
    assertThat(creator.getMaster()).isNull();
    assertThat(invitation.getStatus()).isEqualTo("PENDING");
  }
}
