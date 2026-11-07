package com.influencermatch.backend.creator.tiktok;

import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.security.permission.repository.RolePermissionOverrideRepository;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class TikTokSessionStore {
  // Failures are returned across the transaction boundary so cleanup is committed before throwing.
  public record Result<T>(T value, BusinessException error) {
    public T unwrap() {
      if (error != null) throw error;
      return value;
    }
  }

  private final com.influencermatch.backend.creator.service.CreatorConflictAudit conflicts;
  private final TikTokOAuthSessionRepository sessions;
  private final CreatorRepository creators;
  private final CreatorInvitationRepository invitations;
  private final UserRepository users;
  private final RolePermissionOverrideRepository overrides;
  private final TikTokLoginProperties config;
  private final Clock clock;
  private final com.influencermatch.backend.auth.service.AuthSessionStore authSessions;
  private final SecureRandom random = new SecureRandom();
  @PersistenceContext private EntityManager em;

  public static String cookieName(UUID id) {
    return "im_tiktok_" + id;
  }

  private String hash(String value) {
    return CreatorInvitationService.hashToken(value);
  }

  private BusinessException expired() {
    return new BusinessException(
        ErrorCode.TIKTOK_SESSION_EXPIRED, "TikTok session expired; start again");
  }

  private BusinessException replay() {
    return new BusinessException(
        ErrorCode.TIKTOK_ALREADY_PROCESSED, "TikTok callback already processed");
  }

  private User actor() {
    Permissions.require(Permission.CLAIM_CREATOR_PROFILE);
    return Permissions.actor();
  }

  public void assertUser(User user, long version) {
    em.refresh(user);
    if (user.getRole() != Role.CREATOR
        || user.getStatus() != UserStatus.ACTIVE
        || !user.isEmailVerified()
        || user.getAuthVersion() != version)
      throw new ForbiddenException(
          "An active verified Creator and unchanged authentication are required");
    var current = overrides.findByRole(Role.CREATOR);
    current.forEach(em::refresh);
    if (!RolePermissions.forRole(Role.CREATOR).contains(Permission.CLAIM_CREATOR_PROFILE)
        || current.stream()
            .anyMatch(o -> o.getPermission() == Permission.CLAIM_CREATOR_PROFILE && !o.isEnabled()))
      throw new ForbiddenException("Permission required");
  }

  public void checkInvitation(CreatorInvitation invitation, User user) {
    em.refresh(invitation);
    if (!"PENDING".equals(invitation.getStatus())
        || !invitation
            .getExpiresAt()
            .isAfter(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)))
      throw new ValidationException("Invitation is expired or already processed");
    if (!user.getEmail().equalsIgnoreCase(invitation.getInvitedEmail()))
      throw new ForbiddenException("Invitation belongs to another email");
  }

  private void checkTarget(Creator target, User user) {
    em.refresh(target);
    if (!"TIKTOK".equals(target.getPlatform()))
      throw new ValidationException("A TikTok channel is required");
    if (target.getMaster() != null && !target.getMaster().getUser().getId().equals(user.getId()))
      throw conflicts.rejectForActor(
          user.getId(),
          target.getId(),
          null,
          ErrorCode.CREATOR_ALREADY_LINKED,
          "Channel already linked");
    for (var invitation :
        invitations.findByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByIdAsc(
            target.getId(), "PENDING", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)))
      checkInvitation(invitation, user);
  }

  public void assertSession(TikTokOAuthSession session) {
    config.requireConfigured();
    assertUser(session.getUser(), session.getAuthVersion());
    authSessions.require(session.getAuthSessionId(), session.getUser(), false);
    if (!Objects.equals(session.getClientKey(), config.clientKey()))
      throw new BusinessException(
          ErrorCode.TIKTOK_OAUTH_UNAVAILABLE, "TikTok configuration changed; start again");
    if (session.getTargetCreator() != null)
      checkTarget(session.getTargetCreator(), session.getUser());
    if (session.getInvitation() != null && !"COMPLETED".equals(session.getStatus()))
      checkInvitation(session.getInvitation(), session.getUser());
  }

  @Transactional(noRollbackFor = CreatorOwnershipConflictException.class)
  public TikTokDtos.Started start(TikTokDtos.Start request, String browserBinding) {
    var actor = actor();
    config.requireConfigured();
    long version = actor.getAuthVersion();
    var user =
        users
            .findById(actor.getId())
            .orElseThrow(() -> new ForbiddenException("Creator unavailable"));
    assertUser(user, version);
    Creator target =
        request.creatorId() == null
            ? null
            : creators
                .findById(request.creatorId())
                .orElseThrow(() -> new NotFoundException("Creator not found"));
    CreatorInvitation invitation = null;
    if (request.invitationToken() != null && !request.invitationToken().isBlank()) {
      invitation =
          invitations
              .findByTokenHash(hash(request.invitationToken().trim()))
              .orElseThrow(() -> new ValidationException("Invalid invitation"));
      checkInvitation(invitation, user);
      if (target != null && !target.getId().equals(invitation.getTargetCreator().getId()))
        throw new ValidationException("Invitation target does not match");
      target = invitation.getTargetCreator();
    }
    if (target != null) checkTarget(target, user);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String state = HexFormat.of().formatHex(bytes);
    var session =
        sessions.save(
            TikTokOAuthSession.builder()
                .user(user)
                .authVersion(version)
                .authSessionId(authSessions.currentId())
                .clientKey(config.clientKey())
                .stateHash(hash(state))
                .browserBindingHash(hash(browserBinding))
                .status("NEW")
                .expiresAt(clock.instant().plusSeconds(600))
                .targetCreator(target)
                .invitation(invitation)
                .build());
    return new TikTokDtos.Started(
        session.getId(), config.authorizationUrl(state), session.getExpiresAt());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Result<UUID> reserve(String state, Map<String, String> cookies) {
    config.requireConfigured();
    if (state == null || !state.matches("[0-9a-f]{64}"))
      throw new BusinessException(ErrorCode.TIKTOK_INVALID_STATE, "Invalid TikTok state");
    var session =
        sessions
            .lockByStateHash(hash(state))
            .orElseThrow(
                () ->
                    new BusinessException(ErrorCode.TIKTOK_INVALID_STATE, "Invalid TikTok state"));
    em.refresh(session);
    String binding = cookies.get(cookieName(session.getId()));
    if (binding == null
        || !binding.matches("[0-9a-f]{64}")
        || !MessageDigest.isEqual(
            hash(binding).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            session.getBrowserBindingHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
      throw new ForbiddenException("Use the browser that started this TikTok connection");
    if ("COMPLETED".equals(session.getStatus())) throw replay();
    if (!session.getExpiresAt().isAfter(clock.instant())) {
      clear(session, "EXPIRED");
      return new Result<>(null, expired());
    }
    if (!"NEW".equals(session.getStatus())) throw replay();
    try {
      assertSession(session);
    } catch (BusinessException ex) {
      clear(session, "FAILED");
      return new Result<>(null, ex);
    }
    session.setStatus("EXCHANGING");
    return new Result<>(session.getId(), null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(UUID id) {
    sessions
        .lockById(id)
        .ifPresent(
            s -> {
              em.refresh(s);
              if ("EXCHANGING".equals(s.getStatus())) clear(s, "FAILED");
            });
  }

  @Transactional
  public Result<TikTokDtos.Session> view(UUID id) {
    var actor = actor();
    var session =
        sessions
            .lockById(id)
            .filter(s -> s.getUser().getId().equals(actor.getId()))
            .orElseThrow(() -> new NotFoundException("TikTok session not found"));
    em.refresh(session);
    if (!"COMPLETED".equals(session.getStatus())
        && !session.getExpiresAt().isAfter(clock.instant())) clear(session, "EXPIRED");
    try {
      assertSession(session);
    } catch (BusinessException ex) {
      clear(session, "FAILED");
      return new Result<>(null, ex);
    }
    var account =
        "READY".equals(session.getStatus())
            ? new TikTokDtos.Account(
                session.getOpenId(),
                session.getUsername(),
                session.getDisplayName(),
                session.getMatchStatus() == null ? "UNMATCHED" : session.getMatchStatus())
            : null;
    return new Result<>(
        new TikTokDtos.Session(
            id,
            "TIKTOK",
            session.getStatus(),
            session.getExpiresAt(),
            account,
            session.getLinkedCreatorId(),
            session.getLinkedMasterId()),
        null);
  }

  @Transactional
  public int expire() {
    return sessions.expire(clock.instant());
  }

  private void clear(TikTokOAuthSession session, String status) {
    session.setStatus(status);
    session.setOpenId(null);
    session.setUsername(null);
    session.setDisplayName(null);
    session.setMatchStatus(null);
    session.setLinkedCreatorId(null);
    session.setLinkedMasterId(null);
  }
}
