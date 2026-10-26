package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.creator.dto.ConflictDetailDto;
import com.influencermatch.backend.creator.dto.ConflictResolutionDecision;
import com.influencermatch.backend.creator.dto.ConnectAccountResponse;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorAccountLinkService;
import com.influencermatch.backend.creator.service.CreatorIdentityConflictService;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.oauth.OAuthTokenEncryptionService;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
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
 * Integration test suite for Phase 7.2: Multi-channel Linking & Conflict Resolution.
 * Covers Acceptance Test Cases: TC-08, TC-09, TC-10, TC-11, TC-15, TC-16.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatorAccountLinkIntegrationTest {

  @Mock private CreatorRepository creatorRepository;
  @Mock private CreatorMasterRepository creatorMasterRepository;
  @Mock private CreatorIdentityConflictRepository conflictRepository;
  @Mock private CreatorAccountVerificationRepository verificationRepository;
  @Mock private CreatorProviderIdentityRepository providerIdentityRepository;
  @Mock private CreatorIdentityAuditRepository auditRepository;
  @Mock private ShortlistItemRepository shortlistItemRepository;
  @Mock private BrandCreatorRelationshipRepository relationshipRepository;
  @Mock private UserRepository userRepository;
  @Mock private OAuthTokenEncryptionService encryptionService;
  @Mock private OAuthProofClient oAuthProofClient;

  private CreatorAccountLinkService accountLinkService;
  private CreatorIdentityConflictService conflictService;

  private User kocUser;
  private CreatorMaster masterM1;

  private UUID authenticated(UUID id) {
    var actor=userRepository.findById(id).orElseThrow();actor.setStatus(UserStatus.ACTIVE);actor.setEmailVerified(true);
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,null,actor.getAuthorities()));
    return id;
  }
  @org.junit.jupiter.api.AfterEach void clearAuthentication(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  @BeforeEach
  void setUp() {
    accountLinkService =
        com.influencermatch.backend.support.CreatorServiceFixtures.wire(new CreatorAccountLinkService(
            creatorRepository,
            creatorMasterRepository,
            conflictRepository,
            verificationRepository,
            providerIdentityRepository,
            auditRepository,
            shortlistItemRepository,
            relationshipRepository,
            userRepository,
            encryptionService,
            oAuthProofClient));

    conflictService =
        new CreatorIdentityConflictService(
            conflictRepository,
            creatorRepository,
            auditRepository,
            shortlistItemRepository,
            relationshipRepository);

    when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc_" + inv.getArgument(0));
    when(encryptionService.decrypt(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).replace("enc_", ""));

    kocUser =
        User.builder()
            .id(UUID.randomUUID())
            .email("koc.master@influencermatch.com")
            .fullName("KOC Master")
            .role(Role.CREATOR)
            .status(UserStatus.ACTIVE)
            .build();

    masterM1 =
        CreatorMaster.builder()
            .id(UUID.randomUUID())
            .user(kocUser)
            .canonicalName("KOC Master Channel")
            .status("ACTIVE")
            .build();

    when(userRepository.findById(kocUser.getId())).thenReturn(Optional.of(kocUser));
    when(creatorMasterRepository.findByUserId(kocUser.getId())).thenReturn(Optional.of(masterM1));
  }

  @Test
  @DisplayName("TC-08: Connect 2nd channel already crawled in DB -> reuses account, sets master_id = M1, AUTHORIZED, no duplicate")
  void testTC08_connectSecondChannel_existingCrawledAccount_reusesAndSetsMaster() {
    UUID existingCrawledId = UUID.randomUUID();
    Creator crawledYoutube =
        Creator.builder()
            .id(existingCrawledId)
            .platform("YOUTUBE")
            .externalId("UC_YOUTUBE_CRAWLED_002")
            .userName("koc_official_yt")
            .displayName("KOC YouTube Official")
            .sourceType(CreatorSourceType.PUBLIC)
            .master(null)
            .build();

    when(creatorRepository.findLockedByPlatformAndExternalId("YOUTUBE", "UC_YOUTUBE_CRAWLED_002"))
        .thenReturn(Optional.of(crawledYoutube));

    when(oAuthProofClient.exchangeAndVerify(eq("YOUTUBE"), eq("auth_code_yt_2"), any(), any()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId("UC_YOUTUBE_CRAWLED_002")
            .providerUsername("koc_official_yt")
            .accessToken("yt_access_token_123")
            .refreshToken("yt_refresh_token_123")
            .tokenExpiresAt(LocalDateTime.now().plusHours(1))
            .rawProfileData(Map.of("channelTitle", "KOC YouTube Official"))
            .build());

    when(creatorRepository.save(any(Creator.class))).thenAnswer(inv -> inv.getArgument(0));

    ConnectAccountResponse response =
        accountLinkService.linkAdditionalAccount(authenticated(kocUser.getId()), "YOUTUBE", "auth_code_yt_2");

    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo("LINKED");
    assertThat(response.getAccountId()).isEqualTo(existingCrawledId);
    assertThat(response.getMasterId()).isEqualTo(masterM1.getId());
    assertThat(response.getPlatform()).isEqualTo("YOUTUBE");

    // Case 1 assertion: account reused, not duplicated, master updated, status set to AUTHORIZED
    assertThat(crawledYoutube.getMaster()).isEqualTo(masterM1);
    assertThat(crawledYoutube.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(crawledYoutube.getAuthorizedAt()).isNotNull();

    // Dual references updated for Shortlist and Relationship
    verify(shortlistItemRepository).updateMasterForCreator(existingCrawledId, masterM1);
    verify(relationshipRepository).updateMasterForCreator(existingCrawledId, masterM1);

    // Tokens and audit log saved
    verify(verificationRepository).save(any(CreatorAccountVerification.class));
    verify(auditRepository).save(argThat(audit -> "LINK_ACCOUNT".equals(audit.getEventType())));
  }

  @Test
  @DisplayName("TC-09: Connect 2nd channel never crawled before -> creates new Creator, immediately sets master_id = M1, AUTHORIZED")
  void testTC09_connectSecondChannel_brandNewAccount_createsAndSetsMaster() {
    when(creatorRepository.findLockedByPlatformAndExternalId("TIKTOK", "tt_brand_new_koc"))
        .thenReturn(Optional.empty());

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq("auth_code_tt_new"), any(), any()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId("tt_brand_new_koc")
            .providerUsername("koc_lifestyle_tt")
            .accessToken("tt_token_abc")
            .refreshToken("tt_ref_abc")
            .tokenExpiresAt(LocalDateTime.now().plusDays(60))
            .rawProfileData(Map.of())
            .build());

    when(creatorRepository.save(any(Creator.class))).thenAnswer(inv -> {
      Creator c = inv.getArgument(0);
      if (c.getId() == null) c.setId(UUID.randomUUID());
      return c;
    });

    ConnectAccountResponse response =
        accountLinkService.linkAdditionalAccount(authenticated(kocUser.getId()), "TIKTOK", "auth_code_tt_new");

    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo("CREATED_AND_LINKED");
    assertThat(response.getAccountId()).isNotNull();
    assertThat(response.getMasterId()).isEqualTo(masterM1.getId());
    assertThat(response.getPlatform()).isEqualTo("TIKTOK");

    // Case 2 assertion: new Creator saved directly with master = M1, sourceType = AUTHORIZED
    verify(creatorRepository).save(argThat(c ->
        c.getMaster() != null
            && c.getMaster().getId().equals(masterM1.getId())
            && c.getSourceType() == CreatorSourceType.AUTHORIZED
            && "TIKTOK".equals(c.getPlatform())
            && "tt_brand_new_koc".equals(c.getExternalId())
    ));

    verify(auditRepository).save(argThat(audit -> "LINK_NEW_ACCOUNT".equals(audit.getEventType())));
  }

  @Test
  @DisplayName("TC-10: OAuth ID differs from public ID namespace -> safely maps via username / provider identity")
  void testTC10_oauthIdDifferentNamespace_resolvesViaUsernameAndMapsSafely() {
    Creator tiktokCreator =
        Creator.builder()
            .id(UUID.randomUUID())
            .platform("TIKTOK")
            .externalId("tt_open_id_7788")
            .userName("tiktok_star")
            .sourceType(CreatorSourceType.PUBLIC)
            .build();

    when(creatorRepository.findLockedByPlatformAndExternalId("TIKTOK", "tt_open_id_7788"))
        .thenReturn(Optional.of(tiktokCreator));

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq("auth_code_tt_ns"), any(), any()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId("tt_open_id_7788")
            .providerUsername("tiktok_star")
            .accessToken("tt_access_ns")
            .refreshToken("tt_refresh_ns")
            .tokenExpiresAt(LocalDateTime.now().plusHours(2))
            .rawProfileData(Map.of("open_id", "tt_open_id_7788", "union_id", "tt_union_001"))
            .build());

    when(creatorRepository.save(any(Creator.class))).thenAnswer(inv -> inv.getArgument(0));

    ConnectAccountResponse response =
        accountLinkService.linkAdditionalAccount(authenticated(kocUser.getId()), "TIKTOK", "auth_code_tt_ns");

    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo("LINKED");
    assertThat(tiktokCreator.getMaster()).isEqualTo(masterM1);

    // Verify provider identity recorded to bind OAuth namespace
    verify(providerIdentityRepository).save(argThat(pi ->
        "TIKTOK".equals(pi.getProvider())
            && "tt_open_id_7788".equals(pi.getProviderUserId())
            && "tiktok_star".equals(pi.getProviderUsername())
    ));
  }

  @Test
  @DisplayName("TC-11: Connect 2nd channel belonging to other Master M2 -> creates conflict record, preserves ownership")
  void testTC11_connectSecondChannel_alreadyBelongsToOtherMaster_createsConflictRecord() {
    User otherKocUser = User.builder().id(UUID.randomUUID()).email("other@koc.com").build();
    CreatorMaster otherMasterM2 =
        CreatorMaster.builder()
            .id(UUID.randomUUID())
            .user(otherKocUser)
            .canonicalName("Other Master M2")
            .status("ACTIVE")
            .build();

    Creator alreadyOwnedAccount =
        Creator.builder()
            .id(UUID.randomUUID())
            .platform("TIKTOK")
            .externalId("tt_contested_channel")
            .userName("contested_user")
            .sourceType(CreatorSourceType.AUTHORIZED)
            .master(otherMasterM2) // already owned by M2!
            .build();

    when(creatorRepository.findLockedByPlatformAndExternalId("TIKTOK", "tt_contested_channel"))
        .thenReturn(Optional.of(alreadyOwnedAccount));

    when(oAuthProofClient.exchangeAndVerify(eq("TIKTOK"), eq("auth_code_conflict"), any(), any()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId("tt_contested_channel")
            .providerUsername("contested_user")
            .accessToken("tok_conflict")
            .refreshToken("ref_conflict")
            .tokenExpiresAt(LocalDateTime.now().plusHours(1))
            .rawProfileData(Map.of())
            .build());

    when(conflictRepository.findBySourceAccountId(alreadyOwnedAccount.getId()))
        .thenReturn(Collections.emptyList());

    when(conflictRepository.save(any(CreatorIdentityConflict.class)))
        .thenAnswer(inv -> {
          CreatorIdentityConflict c = inv.getArgument(0);
          c.setId(UUID.randomUUID());
          return c;
        });

    assertThatThrownBy(()->accountLinkService.linkAdditionalAccount(authenticated(kocUser.getId()),"TIKTOK","auth_code_conflict"))
        .isInstanceOf(com.influencermatch.backend.exception.CreatorOwnershipConflictException.class);
    verify(conflictRepository,never()).save(any());verify(creatorRepository,never()).save(any());

  }

  @Test
  @DisplayName("TC-15: Channel re-auth / token expiration -> refreshes tokens without deleting Account or Master data")
  void testTC15_oauthAccessTokenExpired_reauthRefreshesTokensWithoutDeletingData() {
    Creator myOwnChannel =
        Creator.builder()
            .id(UUID.randomUUID())
            .platform("YOUTUBE")
            .externalId("UC_MY_CHANNEL_001")
            .userName("my_channel")
            .sourceType(CreatorSourceType.AUTHORIZED)
            .master(masterM1)
            .build();

    CreatorAccountVerification existingVerification =
        CreatorAccountVerification.builder()
            .id(UUID.randomUUID())
            .creator(myOwnChannel)
            .accessTokenEncrypted("old_expired_enc_token")
            .tokenExpiresAt(LocalDateTime.now().minusDays(1)) // Expired token!
            .status("ACTIVE")
            .build();

    when(creatorRepository.findLockedByPlatformAndExternalId("YOUTUBE", "UC_MY_CHANNEL_001"))
        .thenReturn(Optional.of(myOwnChannel));
    when(verificationRepository.findFirstByCreatorIdAndStatusOrderByVerifiedAtDesc(myOwnChannel.getId(), "ACTIVE"))
        .thenReturn(Optional.of(existingVerification));

    when(oAuthProofClient.exchangeAndVerify(eq("YOUTUBE"), eq("refresh_code_new"), any(), any()))
        .thenReturn(OAuthProofClient.ProofResult.builder()
            .providerUserId("UC_MY_CHANNEL_001")
            .providerUsername("my_channel")
            .accessToken("new_fresh_token_2026")
            .refreshToken("new_fresh_refresh_2026")
            .tokenExpiresAt(LocalDateTime.now().plusHours(2))
            .rawProfileData(Map.of())
            .build());

    ConnectAccountResponse response =
        accountLinkService.linkAdditionalAccount(authenticated(kocUser.getId()), "YOUTUBE", "refresh_code_new");

    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo("ALREADY_LINKED");
    assertThat(response.getAccountId()).isEqualTo(myOwnChannel.getId());

    // Verify token refreshed without deleting account or master
    assertThat(myOwnChannel.getMaster()).isEqualTo(masterM1);
    verify(verificationRepository).save(argThat(v ->
        "enc_new_fresh_token_2026".equals(v.getAccessTokenEncrypted())
            && v.getCreator().getId().equals(myOwnChannel.getId())
    ));

    verify(creatorRepository, never()).delete(any(Creator.class));
    verify(creatorMasterRepository, never()).delete(any(CreatorMaster.class));
  }

  @Test
  @DisplayName("TC-16: Data Manager resolves dispute -> approves channel transfer with complete audit trail")
  void testTC16_dataManagerResolvesConflict_approvesTransferWithAudit() {
    UUID conflictId = UUID.randomUUID();
    User managerUser =
        User.builder()
            .id(UUID.randomUUID())
            .email("admin.manager@influencermatch.com")
            .role(Role.ADMIN)
            .build();

    User oldOwner = User.builder().id(UUID.randomUUID()).email("old.owner@test.com").build();
    CreatorMaster oldMaster = CreatorMaster.builder().id(UUID.randomUUID()).user(oldOwner).build();

    Creator contestedCreator =
        Creator.builder()
            .id(UUID.randomUUID())
            .platform("TIKTOK")
            .externalId("tt_transfer_channel")
            .master(oldMaster)
            .sourceType(CreatorSourceType.AUTHORIZED)
            .build();

    CreatorIdentityConflict conflict =
        CreatorIdentityConflict.builder()
            .id(conflictId)
            .conflictType("ACCOUNT_ALREADY_LINKED")
            .sourceAccount(contestedCreator)
            .targetMaster(masterM1) // KOC user M1 is the claimant
            .status("PENDING_MANAGER")
            .build();

    when(conflictRepository.findById(conflictId)).thenReturn(Optional.of(conflict));
    when(conflictRepository.save(any(CreatorIdentityConflict.class))).thenAnswer(inv -> inv.getArgument(0));

    ConflictDetailDto response = conflictService.resolveConflict(
        conflictId,
        ConflictResolutionDecision.APPROVE_TRANSFER,
        "Contractual transfer verified by Data Manager",
        managerUser);

    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo("RESOLVED");
    assertThat(response.getResolvedByUserId()).isEqualTo(managerUser.getId());

    // Verified ownership transfer to target master M1
    assertThat(contestedCreator.getMaster()).isEqualTo(masterM1);

    // Verify dual references update in Shortlist and Relationship
    verify(shortlistItemRepository).updateMasterForCreator(contestedCreator.getId(), masterM1);
    verify(relationshipRepository).updateMasterForCreator(contestedCreator.getId(), masterM1);

    // Verify audit log recorded for conflict resolution
    verify(auditRepository).save(argThat(audit ->
        "CONFLICT_RESOLVED_TRANSFER".equals(audit.getEventType())
            && audit.getMaster().getId().equals(masterM1.getId())
    ));
  }
}
