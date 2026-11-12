package com.influencermatch.backend.creator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.creator.dto.ConnectAccountResponse;
import com.influencermatch.backend.creator.dto.LinkedAccountDto;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.oauth.OAuthTokenEncryptionService;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CreatorAccountLinkServiceTest {

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

  private CreatorAccountLinkService linkService;

  private UUID userId;
  private User mockUser;
  private CreatorMaster mockMaster;

  private UUID authenticated(UUID id) {
    var actor=userRepository.findById(id).orElseThrow();actor.setStatus(UserStatus.ACTIVE);actor.setEmailVerified(true);
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,null,actor.getAuthorities()));
    return id;
  }
  @org.junit.jupiter.api.AfterEach void clearAuthentication(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
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

    linkService = com.influencermatch.backend.support.CreatorServiceFixtures.wire(new CreatorAccountLinkService(
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
        oAuthProofClient
    ));

    userId = UUID.randomUUID();
    mockUser = User.builder()
        .id(userId)
        .email("creator@influencermatch.com")
        .fullName("Top Creator")
        .role(Role.CREATOR)
        .status(UserStatus.ACTIVE)
        .build();

    mockMaster = CreatorMaster.builder()
        .id(UUID.randomUUID())
        .user(mockUser)
        .canonicalName("Top Creator")
        .status("ACTIVE")
        .build();

    when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
    when(creatorMasterRepository.findByUserId(userId)).thenReturn(Optional.of(mockMaster));
    when(tokenEncryptionService.encrypt(any())).thenReturn("enc_token");
  }

  @Test
  void case1_unattachedCrawledAccount_linkedSuccessfully() {
    String platform = "TIKTOK";
    String authCode = "tt_code_123";
    String providerUserId = "tt_user_id_456";

    Creator crawledCreator = Creator.builder()
        .id(UUID.randomUUID())
        .platform("TIKTOK")
        .externalId(providerUserId)
        .userName("top_creator_tt")
        .sourceType(CreatorSourceType.PUBLIC)
        .master(null)
        .build();

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq(authCode), isNull(), isNull()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId(providerUserId)
            .providerUsername("top_creator_tt")
            .accessToken("raw_access")
            .refreshToken("raw_refresh")
            .tokenExpiresAt(LocalDateTime.now().plusHours(2))
            .build());

    when(creatorRepository.findLockedByPlatformAndExternalId("TIKTOK", providerUserId))
        .thenReturn(Optional.of(crawledCreator));
    when(creatorRepository.findLockedById(crawledCreator.getId()))
        .thenReturn(Optional.of(crawledCreator));
    when(creatorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    ConnectAccountResponse response = linkService.linkAdditionalAccount(authenticated(userId), platform, authCode);

    assertThat(response.getStatus()).isEqualTo("LINKED");
    assertThat(response.getAccountId()).isEqualTo(crawledCreator.getId());
    assertThat(response.getMasterId()).isEqualTo(mockMaster.getId());

    assertThat(crawledCreator.getMaster()).isEqualTo(mockMaster);
    assertThat(crawledCreator.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(crawledCreator.getAuthorizedAt()).isNotNull();

    verify(accountVerificationRepository).save(any());
    verify(providerIdentityRepository).save(any());
    verify(identityAuditRepository).save(any());
    verify(shortlistItemRepository).updateMasterForCreator(crawledCreator.getId(), mockMaster);
    verify(relationshipRepository).updateMasterForCreator(crawledCreator.getId(), mockMaster);
  }

  @Test
  void case2_brandNewAccount_createdAndLinked() {
    String platform = "YOUTUBE";
    String authCode = "yt_code_999";
    String providerUserId = "UC_BRAND_NEW_111";

    when(oAuthProofClient.exchangeAndVerify(eq("YOUTUBE"), eq(authCode), isNull(), isNull()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId(providerUserId)
            .providerUsername("top_creator_vlogs")
            .accessToken("raw_access")
            .refreshToken("raw_refresh")
            .tokenExpiresAt(LocalDateTime.now().plusHours(2))
            .rawProfileData(Map.of("channelTitle", "Top Creator Official Vlogs"))
            .build());

    // Account not in DB
    when(creatorRepository.findLockedByPlatformAndExternalId("YOUTUBE", providerUserId))
        .thenReturn(Optional.empty());

    when(creatorRepository.save(any())).thenAnswer(i -> {
      Creator c = i.getArgument(0);
      c.setId(UUID.randomUUID());
      return c;
    });

    ConnectAccountResponse response = linkService.linkAdditionalAccount(authenticated(userId), platform, authCode);

    assertThat(response.getStatus()).isEqualTo("CREATED_AND_LINKED");
    assertThat(response.getMasterId()).isEqualTo(mockMaster.getId());
    assertThat(response.getExternalId()).isEqualTo(providerUserId);

    ArgumentCaptor<Creator> captor = ArgumentCaptor.forClass(Creator.class);
    verify(creatorRepository).save(captor.capture());
    Creator created = captor.getValue();
    assertThat(created.getMaster()).isEqualTo(mockMaster);
    assertThat(created.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(created.getDisplayName()).isEqualTo("Top Creator Official Vlogs");
  }

  @Test
  void case3_alreadyLinkedToSelf_updatesTokens() {
    String platform = "TIKTOK";
    String authCode = "tt_refresh_code";
    String providerUserId = "tt_already_mine";

    Creator alreadyLinked = Creator.builder()
        .id(UUID.randomUUID())
        .platform("TIKTOK")
        .externalId(providerUserId)
        .userName("top_creator_tt")
        .master(mockMaster) // Already owned by current master!
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq(authCode), isNull(), isNull()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId(providerUserId)
            .providerUsername("top_creator_tt")
            .accessToken("new_access")
            .refreshToken("new_refresh")
            .tokenExpiresAt(LocalDateTime.now().plusHours(2))
            .build());

    when(creatorRepository.findLockedByPlatformAndExternalId("TIKTOK", providerUserId))
        .thenReturn(Optional.of(alreadyLinked));

    ConnectAccountResponse response = linkService.linkAdditionalAccount(authenticated(userId), platform, authCode);

    assertThat(response.getStatus()).isEqualTo("ALREADY_LINKED");
    verify(creatorRepository, never()).save(any());
    verify(accountVerificationRepository).save(any());
  }

  @Test
  void case4_alreadyLinkedToOtherMaster_createsConflictRecord() {
    String platform = "TIKTOK";
    String authCode = "tt_hacker_code";
    String providerUserId = "tt_belongs_to_other";

    CreatorMaster otherMaster = CreatorMaster.builder()
        .id(UUID.randomUUID())
        .canonicalName("Other Creator")
        .build();

    Creator alreadyClaimedByOther = Creator.builder()
        .id(UUID.randomUUID())
        .platform("TIKTOK")
        .externalId(providerUserId)
        .userName("victim_creator")
        .master(otherMaster) // Owned by OTHER master!
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq(authCode), isNull(), isNull()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId(providerUserId)
            .providerUsername("victim_creator")
            .build());

    when(creatorRepository.findLockedByPlatformAndExternalId("TIKTOK", providerUserId))
        .thenReturn(Optional.of(alreadyClaimedByOther));

    assertThatThrownBy(()->linkService.linkAdditionalAccount(authenticated(userId),platform,authCode))
        .isInstanceOfSatisfying(com.influencermatch.backend.exception.CreatorOwnershipConflictException.class,
          e->assertThat(e.code()).isEqualTo(com.influencermatch.backend.exception.ErrorCode.CREATOR_ALREADY_LINKED));
    assertThat(alreadyClaimedByOther.getMaster()).isEqualTo(otherMaster);
    verify(creatorRepository,never()).save(any());verifyNoInteractions(conflictRepository,accountVerificationRepository);

  }

  @Test
  void getLinkedAccounts_returnsList() {
    Creator c1 = Creator.builder()
        .id(UUID.randomUUID())
        .platform("TIKTOK")
        .externalId("tt_1")
        .userName("creator_tt")
        .master(mockMaster)
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    Creator c2 = Creator.builder()
        .id(UUID.randomUUID())
        .platform("YOUTUBE")
        .externalId("yt_2")
        .userName("creator_yt")
        .master(mockMaster)
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    when(creatorRepository.findByMasterId(mockMaster.getId())).thenReturn(List.of(c1, c2));

    List<LinkedAccountDto> result = linkService.getLinkedAccounts(userId);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getPlatform()).isEqualTo("TIKTOK");
    assertThat(result.get(1).getPlatform()).isEqualTo("YOUTUBE");
  }
}
