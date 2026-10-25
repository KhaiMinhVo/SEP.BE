package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.creator.dto.ConnectAccountResponse;
import com.influencermatch.backend.creator.dto.LinkedAccountDto;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.oauth.OAuthTokenEncryptionService;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorAccountLinkService {

  @org.springframework.beans.factory.annotation.Autowired private CreatorConflictAudit conflicts;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
  private final CreatorRepository creatorRepository;
  private final CreatorMasterRepository creatorMasterRepository;
  private final CreatorIdentityConflictRepository conflictRepository;
  private final CreatorAccountVerificationRepository accountVerificationRepository;
  private final CreatorProviderIdentityRepository providerIdentityRepository;
  private final CreatorIdentityAuditRepository identityAuditRepository;
  private final ShortlistItemRepository shortlistItemRepository;
  private final BrandCreatorRelationshipRepository relationshipRepository;
  private final UserRepository userRepository;
  private final OAuthTokenEncryptionService tokenEncryptionService;
  private final OAuthProofClient oAuthProofClient;

  /**
   * Links an additional social media channel (channel 2+) to the KOC's CreatorMaster.
   * Handles 4 distinct linking scenarios.
   */
  @Transactional(noRollbackFor=com.influencermatch.backend.exception.CreatorOwnershipConflictException.class)
  public ConnectAccountResponse linkAdditionalAccount(
      UUID userId, String platform, String authorizationCode) {

    User currentUser = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

    com.influencermatch.backend.security.permission.Permissions.require(com.influencermatch.backend.security.permission.Permission.CLAIM_CREATOR_PROFILE);
    if(!currentUser.getId().equals(com.influencermatch.backend.security.permission.Permissions.actor().getId())
        ||currentUser.getRole()!=com.influencermatch.backend.user.enums.Role.CREATOR
        ||currentUser.getStatus()!=com.influencermatch.backend.user.enums.UserStatus.ACTIVE||!currentUser.isEmailVerified())
      throw new com.influencermatch.backend.exception.ForbiddenException("An active verified Creator is required");
    CreatorMaster master = creatorMasterRepository.findByUserId(userId)
        .orElseThrow(() -> new ResourceNotFoundException("CreatorMaster", "userId", userId));

    // 1. Proof of control via OAuth Provider
    OAuthProofClient.ProofResult proof = oAuthProofClient.exchangeAndVerify(
        platform, authorizationCode, null, null);

    String providerUserId = proof.getProviderUserId();
    String providerUsername = proof.getProviderUsername();
    String normalizedPlatform = platform.toUpperCase();

    creatorRepository.acquireIdentityLock(normalizedPlatform+":"+providerUserId);
    // 2. Check if this account already exists in DB
    Optional<Creator> existingOpt = creatorRepository.findLockedByPlatformAndExternalId(
        normalizedPlatform, providerUserId);

    // CASE 2: Tài khoản hoàn toàn mới chưa từng cào
    if (existingOpt.isEmpty()) {
      log.info("Case 2: Registering brand new channel {}/{} for masterId={}",
          normalizedPlatform, providerUserId, master.getId());

      String displayName = providerUsername;
      if (proof.getRawProfileData() != null && proof.getRawProfileData().containsKey("channelTitle")) {
        displayName = proof.getRawProfileData().get("channelTitle").toString();
      }

      Creator newCreator = Creator.builder()
          .platform(normalizedPlatform)
          .externalId(providerUserId)
          .userName(providerUsername)
          .displayName(displayName)
          .master(master)
          .sourceType(CreatorSourceType.AUTHORIZED)
          .authorizedAt(LocalDateTime.now())
          .isStableId(true)
          .build();
      newCreator = creatorRepository.save(newCreator);

      saveVerificationAndProviderIdentity(newCreator, currentUser, proof, normalizedPlatform);

      audit(master, newCreator, currentUser, "LINK_NEW_ACCOUNT", Map.of(
          "platform", normalizedPlatform,
          "externalId", providerUserId,
          "action", "REGISTER_AND_LINK_NEW_CHANNEL"
      ));

      return ConnectAccountResponse.builder()
          .accountId(newCreator.getId())
          .masterId(master.getId())
          .platform(normalizedPlatform)
          .externalId(providerUserId)
          .userName(providerUsername)
          .status("CREATED_AND_LINKED")
          .message("New channel registered and successfully linked to your master profile")
          .build();
    }

    Creator existing = existingOpt.get();
    em.refresh(existing);

    // CASE 3: Tài khoản đã thuộc chính Master M1 này
    if (existing.getMaster() != null && existing.getMaster().getId().equals(master.getId())) {
      log.info("Case 3: Channel {}/{} already linked to masterId={}, updating tokens",
          normalizedPlatform, providerUserId, master.getId());

      saveVerificationAndProviderIdentity(existing, currentUser, proof, normalizedPlatform);

      audit(master, existing, currentUser, "REFRESH_ACCOUNT_TOKEN", Map.of(
          "platform", normalizedPlatform,
          "externalId", providerUserId,
          "action", "REFRESH_LINKED_CHANNEL_OAUTH"
      ));

      return ConnectAccountResponse.builder()
          .accountId(existing.getId())
          .masterId(master.getId())
          .platform(normalizedPlatform)
          .externalId(providerUserId)
          .userName(existing.getUserName())
          .status("ALREADY_LINKED")
          .message("Channel is already linked to your master profile; authentication tokens refreshed")
          .build();
    }

    if(existing.getMaster()!=null || existing.getSourceType()==CreatorSourceType.AUTHORIZED)
      throw conflicts.reject(existing.getId(),null,com.influencermatch.backend.exception.ErrorCode.CREATOR_ALREADY_LINKED,"Channel belongs to another Creator");

    // CASE 1: Tài khoản đã có trong DB qua crawler (master_id = NULL)
    log.info("Case 1: Linking unattached crawled channel {}/{} to masterId={}",
        normalizedPlatform, providerUserId, master.getId());

    existing.setMaster(master);
    existing.setSourceType(CreatorSourceType.AUTHORIZED);
    existing.setAuthorizedAt(LocalDateTime.now());
    existing = creatorRepository.save(existing);

    saveVerificationAndProviderIdentity(existing, currentUser, proof, normalizedPlatform);

    audit(master, existing, currentUser, "LINK_ACCOUNT", Map.of(
        "platform", normalizedPlatform,
        "externalId", providerUserId,
        "action", "ATTACH_CRAWLED_CHANNEL_TO_MASTER"
    ));

    // Update shortlist & relationship dual reference
    shortlistItemRepository.updateMasterForCreator(existing.getId(), master);
    relationshipRepository.updateMasterForCreator(existing.getId(), master);

    return ConnectAccountResponse.builder()
        .accountId(existing.getId())
        .masterId(master.getId())
        .platform(normalizedPlatform)
        .externalId(providerUserId)
        .userName(existing.getUserName())
        .status("LINKED")
        .message("Channel successfully linked to your master profile")
        .build();
  }

  /**
   * Retrieves all social accounts linked to the user's CreatorMaster.
   */
  @Transactional(readOnly = true)
  public List<LinkedAccountDto> getLinkedAccounts(UUID userId) {
    CreatorMaster master = creatorMasterRepository.findByUserId(userId)
        .orElseThrow(() -> new ResourceNotFoundException("CreatorMaster", "userId", userId));

    List<Creator> creators = creatorRepository.findByMasterId(master.getId());
    return creators.stream()
        .map(c -> LinkedAccountDto.builder()
            .id(c.getId())
            .platform(c.getPlatform())
            .externalId(c.getExternalId())
            .userName(c.getUserName())
            .displayName(c.getDisplayName())
            .avatarUrl(c.getAvatarUrl())
            .profileUrl(c.getProfileUrl())
            .sourceType(c.getSourceType())
            .authorizedAt(c.getAuthorizedAt())
            .build())
        .toList();
  }

  private void saveVerificationAndProviderIdentity(
      Creator creator, User user, OAuthProofClient.ProofResult proof, String platform) {

    String encryptedAccess = tokenEncryptionService.encrypt(proof.getAccessToken());
    String encryptedRefresh = tokenEncryptionService.encrypt(proof.getRefreshToken());

    String verificationMethod = "OAUTH_AUTHORIZATION";
    if (proof.getRawProfileData() != null) {
      Object envObj = proof.getRawProfileData().get("verificationEnvironment");
      if ("OFFICIAL_SANDBOX".equals(envObj)) {
        verificationMethod = "OAUTH_OFFICIAL_SANDBOX";
      } else if ("TESTING_MODE".equals(envObj)) {
        verificationMethod = "OAUTH_TESTING_GOOGLE";
      } else if ("SIMULATION".equals(envObj)) {
        verificationMethod = "OAUTH_SIMULATED_MOCK";
      }
    }

    CreatorAccountVerification verification = CreatorAccountVerification.builder()
        .creator(creator)
        .verifiedByUser(user)
        .verificationMethod(verificationMethod)
        .grantedScopes("openid,profile,offline_access")
        .accessTokenEncrypted(encryptedAccess)
        .refreshTokenEncrypted(encryptedRefresh)
        .tokenExpiresAt(proof.getTokenExpiresAt())
        .status("ACTIVE")
        .verifiedAt(LocalDateTime.now())
        .build();
    accountVerificationRepository.save(verification);

    CreatorProviderIdentity providerIdentity = providerIdentityRepository
        .findByProviderAndProviderUserId(platform, proof.getProviderUserId())
        .orElseGet(() -> CreatorProviderIdentity.builder()
            .creator(creator)
            .provider(platform)
            .providerUserId(proof.getProviderUserId())
            .build());
    providerIdentity.setCreator(creator);
    providerIdentity.setProviderUsername(proof.getProviderUsername());
    providerIdentity.setRawProfileData(proof.getRawProfileData());
    providerIdentityRepository.save(providerIdentity);
  }

  private void audit(
      CreatorMaster master, Creator creator, User user, String eventType, Map<String, Object> details) {
    CreatorIdentityAudit audit = CreatorIdentityAudit.builder()
        .eventType(eventType)
        .master(master)
        .account(creator)
        .performedBy(user)
        .details(details)
        .build();
    identityAuditRepository.save(audit);
  }
}
