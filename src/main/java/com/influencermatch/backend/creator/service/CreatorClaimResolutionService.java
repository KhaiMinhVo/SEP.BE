package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.creator.dto.OAuthCallbackResponse;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.oauth.OAuthTokenEncryptionService;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorClaimResolutionService {

  private final CreatorRepository creatorRepository;
  private final CreatorMasterRepository creatorMasterRepository;
  private final CreatorInvitationRepository creatorInvitationRepository;
  private final CreatorAccountVerificationRepository accountVerificationRepository;
  private final CreatorProviderIdentityRepository providerIdentityRepository;
  private final CreatorIdentityAuditRepository identityAuditRepository;
  private final ShortlistItemRepository shortlistItemRepository;
  private final BrandCreatorRelationshipRepository relationshipRepository;
  private final UserRepository userRepository;
  private final OAuthTokenEncryptionService tokenEncryptionService;
  private final OAuthProofClient oAuthProofClient;

  /**
   * Resolves the OAuth callback for profile claim atomically.
   */
  @Transactional
  public OAuthCallbackResponse resolveClaim(
      String platform, String code, String rawToken, User explicitUser) {

    // 1. Verify invitation token first
    String tokenHash = CreatorInvitationService.hashToken(rawToken.trim());
    CreatorInvitation invitation = creatorInvitationRepository.findByTokenHash(tokenHash)
        .orElseThrow(() -> new ResourceNotFoundException("Invitation", "token", "Invalid invitation token"));

    if (!"PENDING".equalsIgnoreCase(invitation.getStatus())) {
      throw new ConflictException(
          ErrorCode.INVITATION_ALREADY_PROCESSED,
          "Invitation has already been " + invitation.getStatus());
    }

    if (invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
      invitation.setStatus("EXPIRED");
      creatorInvitationRepository.save(invitation);
      throw new ConflictException(ErrorCode.INVITATION_EXPIRED, "Invitation has expired");
    }

    UUID targetCreatorId = invitation.getTargetCreator().getId();

    // 2. Lock target Creator account (PESSIMISTIC_WRITE)
    Creator creator = creatorRepository.findLockedById(targetCreatorId)
        .orElseThrow(() -> new ResourceNotFoundException("Creator", "id", targetCreatorId));

    if (creator.getMaster() != null || creator.getSourceType() == CreatorSourceType.AUTHORIZED) {
      throw new ConflictException(
          ErrorCode.CREATOR_ALREADY_LINKED,
          "Creator account has already been claimed and linked to a Master");
    }

    // 3. Exchange code and verify Proof of Control with external OAuth Provider
    OAuthProofClient.ProofResult proof = oAuthProofClient.exchangeAndVerify(
        platform, code, creator.getExternalId(), creator.getUserName());

    // Proof check:
    // For YouTube: channel id must match externalId
    // For TikTok: username must match userName (normalized)
    boolean matches = false;
    if ("YOUTUBE".equalsIgnoreCase(platform) || "GOOGLE".equalsIgnoreCase(platform)) {
      matches = creator.getExternalId().equalsIgnoreCase(proof.getProviderUserId());
    } else if ("TIKTOK".equalsIgnoreCase(platform)) {
      String expected = creator.getUserName().replace("@", "").trim();
      String actual = proof.getProviderUsername().replace("@", "").trim();
      matches = expected.equalsIgnoreCase(actual);
    } else {
      // General match
      matches = creator.getExternalId().equalsIgnoreCase(proof.getProviderUserId())
          || creator.getUserName().equalsIgnoreCase(proof.getProviderUsername());
    }

    if (!matches) {
      log.warn("Proof of control mismatch for creatorId={}: expected externalId='{}'/userName='{}', actual providerUserId='{}'/providerUsername='{}'",
          creator.getId(), creator.getExternalId(), creator.getUserName(), proof.getProviderUserId(), proof.getProviderUsername());
      throw new ConflictException(
          ErrorCode.IDENTITY_MISMATCH,
          "Proof of control failed: Authenticated account does not match target creator");
    }

    // 4. Resolve Claimant User
    User claimantUser = explicitUser != null ? explicitUser : Permissions.actorOrNull();
    if (claimantUser == null) {
      claimantUser = userRepository.findByEmail(invitation.getInvitedEmail())
          .orElseGet(() -> {
            User newUser = User.builder()
                .email(invitation.getInvitedEmail())
                .fullName(creator.getDisplayName() != null ? creator.getDisplayName() : creator.getUserName())
                .role(Role.CREATOR)
                .status(UserStatus.ACTIVE)
                .authVersion(1L)
                .passwordHash("$2a$10$A/bxwOtUf1iUtnhbppaG5uBmF/iK7f100XS/H/Y.VpjKyRqiEUh52")
                .build();
            return userRepository.save(newUser);
          });
    }

    // 5. Find or create CreatorMaster M1 for claimantUser
    User finalClaimant = claimantUser;
    CreatorMaster master = creatorMasterRepository.findByUserId(claimantUser.getId())
        .orElseGet(() -> {
          String canonicalName = finalClaimant.getFullName() != null && !finalClaimant.getFullName().isBlank()
              ? finalClaimant.getFullName()
              : (creator.getDisplayName() != null ? creator.getDisplayName() : creator.getUserName());
          CreatorMaster newMaster = CreatorMaster.builder()
              .user(finalClaimant)
              .canonicalName(canonicalName)
              .status("ACTIVE")
              .build();
          return creatorMasterRepository.save(newMaster);
        });

    // 6. Update Creator: link to Master, set AUTHORIZED
    creator.setMaster(master);
    creator.setSourceType(CreatorSourceType.AUTHORIZED);
    creator.setAuthorizedAt(LocalDateTime.now());
    creatorRepository.save(creator);

    // 7. Encrypt tokens and save CreatorAccountVerification
    String encryptedAccess = tokenEncryptionService.encrypt(proof.getAccessToken());
    String encryptedRefresh = tokenEncryptionService.encrypt(proof.getRefreshToken());

    CreatorAccountVerification verification = CreatorAccountVerification.builder()
        .creator(creator)
        .verifiedByUser(claimantUser)
        .verificationMethod("OAUTH_AUTHORIZATION")
        .grantedScopes("openid,profile,offline_access")
        .accessTokenEncrypted(encryptedAccess)
        .refreshTokenEncrypted(encryptedRefresh)
        .tokenExpiresAt(proof.getTokenExpiresAt())
        .status("ACTIVE")
        .verifiedAt(LocalDateTime.now())
        .build();
    accountVerificationRepository.save(verification);

    // 8. Save CreatorProviderIdentity
    CreatorProviderIdentity providerIdentity = providerIdentityRepository
        .findByProviderAndProviderUserId(platform.toUpperCase(), proof.getProviderUserId())
        .orElseGet(() -> CreatorProviderIdentity.builder()
            .creator(creator)
            .provider(platform.toUpperCase())
            .providerUserId(proof.getProviderUserId())
            .build());
    providerIdentity.setCreator(creator);
    providerIdentity.setProviderUsername(proof.getProviderUsername());
    providerIdentity.setRawProfileData(proof.getRawProfileData());
    providerIdentityRepository.save(providerIdentity);

    // 9. Mark invitation ACCEPTED
    invitation.setStatus("ACCEPTED");
    creatorInvitationRepository.save(invitation);

    // 10. Audit log
    CreatorIdentityAudit audit = CreatorIdentityAudit.builder()
        .eventType("CLAIM_MASTER")
        .master(master)
        .account(creator)
        .performedBy(claimantUser)
        .details(Map.of(
            "platform", platform,
            "externalId", creator.getExternalId(),
            "providerUserId", proof.getProviderUserId(),
            "action", "INITIAL_PROFILE_CLAIM"
        ))
        .build();
    identityAuditRepository.save(audit);

    // 11. Dual reference auto-update: update master_id for shortlist and relationship records
    shortlistItemRepository.updateMasterForCreator(creator.getId(), master);
    relationshipRepository.updateMasterForCreator(creator.getId(), master);

    log.info("Successfully claimed creatorId={} into masterId={} for userId={}",
        creator.getId(), master.getId(), claimantUser.getId());

    return OAuthCallbackResponse.builder()
        .masterId(master.getId())
        .creatorId(creator.getId())
        .canonicalName(master.getCanonicalName())
        .status("AUTHORIZED")
        .build();
  }
}
