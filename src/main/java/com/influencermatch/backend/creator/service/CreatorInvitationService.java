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
@Service
@RequiredArgsConstructor
public class CreatorInvitationService {

  private final CreatorRepository creatorRepository;
  private final CreatorInvitationRepository creatorInvitationRepository;
  private final BrandProfileRepository brandProfileRepository;
  private final CampaignRepository campaignRepository;
  private final UserRepository userRepository;

  @org.springframework.beans.factory.annotation.Autowired private com.influencermatch.backend.creator.email.ClaimEmailOutboxService outbox;
  @org.springframework.beans.factory.annotation.Autowired private CreatorConflictAudit conflicts;
  @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
  @org.springframework.beans.factory.annotation.Value("${application.auth.creator-invitation.frontend-base-url:http://localhost:5173}") private String frontend;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * Generates a 32-byte secure random token and stores its SHA-256 hash in the database.
   */
  @Transactional(noRollbackFor=com.influencermatch.backend.exception.CreatorOwnershipConflictException.class)
  @org.springframework.security.access.prepost.PreAuthorize("hasRole('BRAND') and hasAuthority('MANAGE_OWN_SHORTLIST')")
  public InvitationResponse sendInvitation(UUID creatorId, String email, UUID brandId, UUID campaignId) {
    Permissions.require(com.influencermatch.backend.security.permission.Permission.MANAGE_OWN_SHORTLIST);
    User currentUser=Permissions.actor();
    if(currentUser.getRole()!=com.influencermatch.backend.user.enums.Role.BRAND)
      throw new com.influencermatch.backend.exception.ForbiddenException("Only Brands can invite a channel");
    BrandProfile brand=brandProfileRepository.findByUserId(currentUser.getId())
        .filter(b->brandId==null||b.getId().equals(brandId))
        .orElseThrow(()->new com.influencermatch.backend.exception.NotFoundException("Brand profile not found"));
    Campaign campaign=campaignId==null?null:campaignRepository.findById(campaignId)
        .filter(c->c.getBrandProfile().getId().equals(brand.getId()))
        .orElseThrow(()->new com.influencermatch.backend.exception.NotFoundException("Campaign not found"));
    Creator creator=creatorRepository.findLockedById(creatorId)
        .orElseThrow(()->new ResourceNotFoundException("Creator","id",creatorId));
    em.refresh(creator);
    if(creator.getMaster()!=null||creator.getSourceType()==CreatorSourceType.AUTHORIZED)
      throw conflicts.reject(creatorId,null,ErrorCode.CREATOR_ALREADY_LINKED,"Channel already has an owner");
    LocalDateTime now=LocalDateTime.now(clock);
    var pending=creatorInvitationRepository.findFirstByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(creatorId,"PENDING",now);
    if(pending.isPresent())throw conflicts.reject(creatorId,pending.get().getId(),ErrorCode.CREATOR_INVITATION_PENDING,"A pending invitation already exists for this channel");
    outbox.requireConfigured();
    // 1. Generate 32-byte raw token and SHA-256 hash
    byte[] randomBytes = new byte[32];
    secureRandom.nextBytes(randomBytes);
    String rawToken = HexFormat.of().formatHex(randomBytes);
    String tokenHash = hashToken(rawToken);

    LocalDateTime expiresAt = now.plusDays(7);

    // 2. Persist CreatorInvitation
    CreatorInvitation invitation = CreatorInvitation.builder()
        .targetCreator(creator)
        .campaign(campaign)
        .brand(brand)
        .invitedBy(currentUser)
        .invitedEmail(email.trim().toLowerCase(java.util.Locale.ROOT))
        .tokenHash(tokenHash)
        .status("PENDING")
        .expiresAt(expiresAt)
        .build();

    CreatorInvitation saved = creatorInvitationRepository.save(invitation);

    var base=java.net.URI.create(frontend);
    if(!java.util.Set.of("http","https").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null)
      throw new IllegalStateException("Invalid claim frontend base URL");
    String claimUrl=org.springframework.web.util.UriComponentsBuilder.fromUriString(frontend.replaceAll("/+$","")+"/claim-profile")
        .queryParam("token",rawToken).build().encode().toUriString();
    outbox.enqueue(saved,claimUrl);

    return InvitationResponse.builder()
        .id(saved.getId())
        .creatorId(creator.getId())
        .email(saved.getInvitedEmail())
        .status(saved.getStatus())
        .emailStatus("QUEUED")
        .expiresAt(saved.getExpiresAt())
        .build();
  }

  /**
   * Verifies the authenticity and expiration of an invitation token.
   */
  @Transactional(readOnly=true)
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

    if (!invitation.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
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
