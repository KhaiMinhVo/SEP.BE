package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.auth.dto.CreatorRegistrationDtos.*;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.jwt.*;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class CreatorRegistrationInvitationService {
  private final UserRepository users;
  private final RegistrationSupport registration;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
  private final com.influencermatch.backend.creator.repository.CreatorMasterRepository masters;
  private final CreatorRegistrationInvitationRepository invitations;
  private final CreatorRegistrationInvitationDeliveryRepository deliveries;
  private final VerificationEmailSender sender;
  private final EmailVerificationService verification;
  private final PasswordEncoder encoder;
  private final AuditService audit;
  private final Clock clock;
  private final String frontend;
  private final SecureRandom random = new SecureRandom();

  public CreatorRegistrationInvitationService(
      UserRepository users,
      com.influencermatch.backend.creator.repository.CreatorMasterRepository masters,
      CreatorRegistrationInvitationRepository invitations,
      CreatorRegistrationInvitationDeliveryRepository deliveries,
      VerificationEmailSender sender,
      EmailVerificationService verification,
      PasswordEncoder encoder,
      AuditService audit,
      Clock clock,
      RegistrationSupport registration,
      @Value("${application.auth.creator-invitation.frontend-base-url:http://localhost:5173}")
          String frontend) {
    this.users = users;
    this.masters = masters;
    this.invitations = invitations;
    this.deliveries = deliveries;
    this.sender = sender;
    this.registration = registration;
    this.verification = verification;
    this.encoder = encoder;
    this.audit = audit;
    this.clock = clock;
    var uri = java.net.URI.create(frontend);
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getUserInfo() != null)
      throw new IllegalArgumentException(
          "Creator invitation frontend must be an absolute HTTP(S) base URL");
    this.frontend = frontend.replaceAll("/+$", "");
  }

  @Transactional
  @PreAuthorize("hasAuthority('INVITE_CREATOR')")
  public InvitationResponse invite(InvitationRequest request) {
    Permissions.require(Permission.INVITE_CREATOR);
    return issue(request.email(), Permissions.actor().getId(), null);
  }

  /** Called only after a live cooperation invitation and its recipient have been checked. */
  @Transactional
  public void inviteFromCooperation(String email, UUID sponsor, String cooperationToken) {
    users.lockRegistrationEmail(email);
    var existing = users.findByEmail(email);
    if (existing.isPresent()) {
      String link = UriComponentsBuilder.fromUriString(frontend + "/cooperation-invitations")
          .queryParam("token", cooperationToken).build().encode().toUriString();
      sender.sendCooperationAccountAccess(email, link, existing.get().getRole() == Role.CREATOR);
      return;
    }
    issue(email, sponsor, cooperationToken);
  }

  private InvitationResponse issue(String email, UUID sponsor, String cooperationToken) {
    sender.requireConfigured();
    users.lockRegistrationEmail(email);
    if (users.existsByEmail(email) && cooperationToken != null) return null;
    if (users.existsByEmail(email))
      throw new BusinessException(
          ErrorCode.EMAIL_ALREADY_EXISTS, "This email already has an account");
    Instant now = clock.instant();
    var invitation = invitations.findByEmail(email).orElseGet(CreatorRegistrationInvitation::new);
    if ((invitation.getLastSentAt() != null
            && now.isBefore(invitation.getLastSentAt().plusSeconds(60)))
        || deliveries.countByEmailAndSentAtAfter(email, now.minusSeconds(3600)) >= 5)
      throw new EmailRequestRateLimitedException(
          deliveries.countByEmailAndSentAtAfter(email, now.minusSeconds(3600)) >= 5 ? 3600
              : java.time.Duration.between(now, invitation.getLastSentAt().plusSeconds(60)).getSeconds() + 1);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    if (invitation.getId() == null) invitation.setId(UUID.randomUUID());
    invitation.setEmail(email);
    invitation.setTokenHash(hash(token));
    invitation.setConsumedAt(null);
    invitation.setExpiresAt(now.plusSeconds(48 * 3600));
    invitation.setLastSentAt(now);
    invitation.setInvitedBy(sponsor);
    invitations.save(invitation);
    deliveries.deleteByEmailAndSentAtBefore(email, now.minusSeconds(3600));
    deliveries.save(new CreatorRegistrationInvitationDelivery(UUID.randomUUID(), email, now));
    audit.recordAs(
        cooperationToken == null ? sponsor : null,
        "INVITE_CREATOR",
        "CreatorRegistrationInvitation",
        invitation.getId(),
        null,
        Map.of("email", email, "expiresAt", invitation.getExpiresAt().toString()),
        cooperationToken == null ? "Gửi thư mời đăng ký tài khoản Creator"
            : "Người nhận lời mời hợp tác yêu cầu email đăng ký Creator");
    var builder = UriComponentsBuilder.fromUriString(frontend + "/auth/creator/register")
        .queryParam("token", token);
    if (cooperationToken != null) builder.queryParam("cooperationToken", cooperationToken);
    String link = builder.build().encode().toUriString();
    sender.sendCreatorInvitation(email, link);
    return response(invitation);
  }

  @Transactional(readOnly = true)
  public InvitationResponse resolve(String token) {
    return response(valid(token));
  }

  @Transactional
  public void register(RegisterCreatorRequest request) {
    registration.validate(request.policyAcceptance());
    // Serialize only this email, then reload the invitation before consuming it.
    sender.requireConfigured();
    var invitation = valid(request.invitationToken());
    users.lockRegistrationEmail(invitation.getEmail());
    em.refresh(invitation);
    invitation = valid(request.invitationToken());
    if (users.existsByEmail(invitation.getEmail()))
      throw new BusinessException(
          ErrorCode.EMAIL_ALREADY_EXISTS, "This email already has an account");
    User user =
        users.saveAndFlush(
            User.builder()
                .email(invitation.getEmail())
                .fullName(request.fullName().trim())
                .passwordHash(encoder.encode(request.password()))
                .role(Role.CREATOR)
                .status(UserStatus.ACTIVE)
                .emailVerified(false)
                .build());
    masters.save(
        com.influencermatch.backend.creator.model.CreatorMaster.builder()
            .user(user)
            .canonicalName(user.getFullName())
            .status("ACTIVE")
            .build());
    invitation.setConsumedAt(clock.instant());
    registration.consent(user, request.policyAcceptance(), "CREATOR_INVITATION");
    verification.sendInitial(user);
  }

  private CreatorRegistrationInvitation valid(String token) {
    return invitations
        .findByTokenHash(hash(token))
        .filter(i -> i.getConsumedAt() == null && i.getExpiresAt().isAfter(clock.instant()))
        .orElseThrow(this::invalid);
  }

  private BusinessException invalid() {
    return new BusinessException(
        ErrorCode.CREATOR_INVITATION_INVALID, "Invitation is invalid, expired or already used");
  }

  private InvitationResponse response(CreatorRegistrationInvitation i) {
    return new InvitationResponse(i.getId(), i.getEmail(), i.getExpiresAt());
  }

  private String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
