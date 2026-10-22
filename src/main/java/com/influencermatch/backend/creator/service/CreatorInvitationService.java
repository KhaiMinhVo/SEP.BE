package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.dto.InvitationResponse;
import com.influencermatch.backend.creator.dto.VerifyTokenResponse;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorInvitation;
import com.influencermatch.backend.creator.repository.CreatorInvitationRepository;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service("creatorClaimInvitationService")
@RequiredArgsConstructor
public class CreatorInvitationService {

  private final CreatorRepository creatorRepository;
  private final CreatorInvitationRepository creatorInvitationRepository;
  private final BrandProfileRepository brandProfileRepository;
  private final CampaignRepository campaignRepository;
  private final UserRepository userRepository;

  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * Generates a 32-byte secure random token and stores its SHA-256 hash in the database.
   */
  @Transactional
  public InvitationResponse sendInvitation(UUID creatorId, String email, UUID brandId, UUID campaignId) {
    Creator creator = creatorRepository.findById(creatorId)
        .orElseThrow(() -> new ResourceNotFoundException("Creator", "id", creatorId));

    if (creator.getMaster() != null || creator.getSourceType() == CreatorSourceType.AUTHORIZED) {
      throw new ConflictException(
          ErrorCode.CREATOR_ALREADY_LINKED,
          "Creator account has already been claimed and linked to a Master");
    }

    User currentUser = Permissions.actor();
    if (currentUser == null) {
      throw new IllegalStateException("Authentication required to send creator invitation");
    }

    BrandProfile brand = null;
    if (brandId != null) {
      brand = brandProfileRepository.findById(brandId)
          .orElseThrow(() -> new ResourceNotFoundException("BrandProfile", "id", brandId));
    } else {
      brand = brandProfileRepository.findByUserId(currentUser.getId()).orElse(null);
    }

    Campaign campaign = null;
    if (campaignId != null) {
      campaign = campaignRepository.findById(campaignId)
          .orElseThrow(() -> new ResourceNotFoundException("Campaign", "id", campaignId));
    }

    // 1. Generate 32-byte raw token and SHA-256 hash
    byte[] randomBytes = new byte[32];
    secureRandom.nextBytes(randomBytes);
    String rawToken = HexFormat.of().formatHex(randomBytes);
    String tokenHash = hashToken(rawToken);

    LocalDateTime expiresAt = LocalDateTime.now().plusDays(7);

    // 2. Persist CreatorInvitation
    CreatorInvitation invitation = CreatorInvitation.builder()
        .targetCreator(creator)
        .campaign(campaign)
        .brand(brand)
        .invitedBy(currentUser)
        .invitedEmail(email.trim().toLowerCase())
        .tokenHash(tokenHash)
        .status("PENDING")
        .expiresAt(expiresAt)
        .build();

    CreatorInvitation saved = creatorInvitationRepository.save(invitation);

    // 3. Send email to creator with claim link

    log.info("Created profile claim invitation id={} for creatorId={}", saved.getId(), creator.getId());

    return InvitationResponse.builder()
        .id(saved.getId())
        .creatorId(creator.getId())
        .email(saved.getInvitedEmail())
        .status(saved.getStatus())
        .expiresAt(saved.getExpiresAt())
        .rawToken(rawToken)
        .build();
  }

  /**
   * Verifies the authenticity and expiration of an invitation token.
   */
  @Transactional
  public VerifyTokenResponse verifyInvitationToken(String rawToken) {
    if (rawToken == null || rawToken.trim().isEmpty()) {
      throw new ResourceNotFoundException("Invitation", "token", "Missing or empty token");
    }

    String tokenHash = hashToken(rawToken.trim());
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
      throw new ConflictException(
          ErrorCode.INVITATION_EXPIRED,
          "Invitation has expired on " + invitation.getExpiresAt());
    }

    Creator creator = invitation.getTargetCreator();
    return VerifyTokenResponse.builder()
        .creatorId(creator.getId())
        .platform(creator.getPlatform())
        .userName(creator.getUserName())
        .displayName(creator.getDisplayName())
        .avatarUrl(creator.getAvatarUrl())
        .build();
  }

  public static String hashToken(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
