package com.influencermatch.backend.creator.meta;

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
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class MetaSessionStore {
  public record Exchange(UUID sessionId, String platform, OAuthFlow oauthFlow) {}

  public record Selection(String platform, MetaDtos.Proof proof, OAuthFlow oauthFlow) {
    @Override
    public String toString() {
      return "MetaSelection[redacted]";
    }
  }

  private final MetaOAuthSessionRepository sessions;
  private final CreatorRepository creators;
  private final CreatorInvitationRepository invitations;
  private final UserRepository users;
  private final RolePermissionOverrideRepository overrides;
  private final MetaOAuthProperties config;
  private final InstagramOAuthProperties instagram;
  private final MetaPayloadCipher cipher;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  @PersistenceContext private EntityManager em;

  public Instant now() {
    return clock.instant();
  }

  public void assertUser(User user, long version) {
    if (user.getRole() != Role.CREATOR
        || user.getStatus() != UserStatus.ACTIVE
        || !user.isEmailVerified()
        || user.getAuthVersion() != version)
      throw new ForbiddenException("An active verified Creator and unchanged session are required");
    // Fresh read also protects a long provider request from a mid-request permission change.
    var currentOverrides = overrides.findByRole(Role.CREATOR);
    currentOverrides.forEach(em::refresh);
    if (!RolePermissions.forRole(Role.CREATOR).contains(Permission.CLAIM_CREATOR_PROFILE)
        || currentOverrides.stream()
            .anyMatch(o -> o.getPermission() == Permission.CLAIM_CREATOR_PROFILE && !o.isEnabled()))
      throw new ForbiddenException("Permission required");
  }

  public User actor() {
    Permissions.require(Permission.CLAIM_CREATOR_PROFILE);
    return Permissions.actor();
  }

  private MetaOAuthSession owned(UUID id) {
    User actor = actor();
    var session =
        sessions
            .findById(id)
            .filter(s -> s.getUser().getId().equals(actor.getId()))
            .orElseThrow(() -> new NotFoundException("Meta session not found"));
    var user = session.getUser();
    em.refresh(user);
    assertUser(user, session.getAuthVersion());
    return session;
  }

  public void requireReady(MetaOAuthSession session) {
    if (!session.getExpiresAt().isAfter(now()))
      throw new ConflictException(
          ErrorCode.META_OAUTH_SESSION_EXPIRED, "Meta session expired; start again");
    if (!"READY".equals(session.getStatus()))
      throw new ConflictException(
          ErrorCode.META_OAUTH_ALREADY_PROCESSED, "Meta session is not ready for confirmation");
  }

  @Transactional
  public MetaDtos.Started start(MetaDtos.Start request, String browserBinding) {
    return start(request, browserBinding, OAuthFlow.FACEBOOK_LOGIN);
  }

  @Transactional
  public MetaDtos.Started startInstagram(MetaDtos.InstagramStart request, String browserBinding) {
    return start(
        new MetaDtos.Start("INSTAGRAM", request.creatorId(), request.invitationToken()),
        browserBinding,
        OAuthFlow.INSTAGRAM_LOGIN);
  }

  public void requireConfigured(OAuthFlow flow) {
    if (flow == OAuthFlow.INSTAGRAM_LOGIN) instagram.requireConfigured();
    else config.requireConfigured();
  }

  private MetaDtos.Started start(MetaDtos.Start request, String browserBinding, OAuthFlow flow) {
    var actor = actor();
    long version = actor.getAuthVersion();
    requireConfigured(flow);
    var user =
        users
            .findById(actor.getId())
            .orElseThrow(() -> new ForbiddenException("Creator unavailable"));
    em.refresh(user);
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
              .findByTokenHash(CreatorInvitationService.hashToken(request.invitationToken().trim()))
              .orElseThrow(() -> new ValidationException("Invalid invitation"));
      checkInvitation(invitation, user);
      if (target != null && !target.getId().equals(invitation.getTargetCreator().getId()))
        throw new ValidationException("Invitation target does not match");
      target = invitation.getTargetCreator();
    }
    if (target != null
        && (!request.platform().equals(target.getPlatform())
            || !Boolean.TRUE.equals(target.getIsStableId())))
      throw new ConflictException(
          ErrorCode.META_ACCOUNT_NOT_MATCHED,
          "Channel needs a matching official ID or manual Claim");
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String state = HexFormat.of().formatHex(bytes);
    var session =
        sessions.save(
            MetaOAuthSession.builder()
                .user(user)
                .authVersion(version)
                .platform(request.platform())
                .oauthFlow(flow)
                .browserBindingHash(CreatorInvitationService.hashToken(browserBinding))
                .stateHash(CreatorInvitationService.hashToken(state))
                .status("NEW")
                .expiresAt(now().plusSeconds(600))
                .targetCreator(target)
                .invitation(invitation)
                .build());
    return new MetaDtos.Started(
        session.getId(),
        flow == OAuthFlow.INSTAGRAM_LOGIN
            ? instagram.authorizationUrl(state)
            : config.authorizationUrl(state),
        session.getExpiresAt());
  }

  public void checkInvitation(CreatorInvitation invitation, User user) {
    if (!"PENDING".equals(invitation.getStatus()))
      throw new ConflictException(
          ErrorCode.INVITATION_ALREADY_PROCESSED, "Invitation already processed");
    if (!invitation.getExpiresAt().isAfter(LocalDateTime.ofInstant(now(), ZoneOffset.UTC)))
      throw new ConflictException(ErrorCode.INVITATION_EXPIRED, "Invitation expired");
    if (!user.getEmail().equalsIgnoreCase(invitation.getInvitedEmail()))
      throw new ForbiddenException("Invitation belongs to another email");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Exchange reserve(String state, Map<String, String> cookies, OAuthFlow callbackFlow) {
    requireConfigured(callbackFlow);
    if (state == null || !state.matches("[0-9a-f]{64}"))
      throw new BusinessException(ErrorCode.META_OAUTH_INVALID_STATE, "Invalid OAuth state");
    var session =
        sessions
            .lockByStateHash(CreatorInvitationService.hashToken(state))
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.META_OAUTH_INVALID_STATE, "Invalid OAuth state"));
    em.refresh(session);
    if (session.getOauthFlow() != callbackFlow)
      throw new BusinessException(
          ErrorCode.META_OAUTH_INVALID_STATE, "OAuth callback flow does not match session");
    String binding = cookies.get(cookieName(session.getId()));
    if (binding == null
        || !binding.matches("[0-9a-f]{64}")
        || !java.security.MessageDigest.isEqual(
            CreatorInvitationService.hashToken(binding)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            session.getBrowserBindingHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
      throw new ForbiddenException("Use the browser that started this Meta connection");
    if (!session.getExpiresAt().isAfter(now()))
      throw new ConflictException(ErrorCode.META_OAUTH_SESSION_EXPIRED, "Meta session expired");
    if (!"NEW".equals(session.getStatus()))
      throw new ConflictException(
          ErrorCode.META_OAUTH_ALREADY_PROCESSED, "OAuth callback already processed");
    var user = session.getUser();
    em.refresh(user);
    assertUser(user, session.getAuthVersion());
    session.setStatus("EXCHANGING");
    return new Exchange(session.getId(), session.getPlatform(), session.getOauthFlow());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public MetaDtos.Callback ready(UUID id, MetaDtos.Proof proof) {
    var session = sessions.lockById(id).orElseThrow();
    em.refresh(session);
    var user = session.getUser();
    em.refresh(user);
    assertUser(user, session.getAuthVersion());
    if (!session.getExpiresAt().isAfter(now()) || !proof.tokenExpiresAt().isAfter(now()))
      throw new ConflictException(ErrorCode.META_OAUTH_SESSION_EXPIRED, "Meta session expired");
    if (!"EXCHANGING".equals(session.getStatus()))
      throw new ConflictException(
          ErrorCode.META_OAUTH_ALREADY_PROCESSED, "OAuth callback already processed");
    if (proof.tokenExpiresAt().isBefore(session.getExpiresAt()))
      session.setExpiresAt(proof.tokenExpiresAt());
    session.setPayloadCiphertext(cipher.encrypt(session.getOauthFlow(), proof));
    session.setStatus("READY");
    return new MetaDtos.Callback(id, "READY");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(UUID id) {
    var session = sessions.lockById(id).orElseThrow();
    em.refresh(session);
    if ("EXCHANGING".equals(session.getStatus())) {
      session.setStatus("FAILED");
      session.setPayloadCiphertext(null);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failReady(UUID id) {
    var session = sessions.lockById(id).orElseThrow();
    em.refresh(session);
    if ("READY".equals(session.getStatus())) {
      session.setStatus("FAILED");
      session.setPayloadCiphertext(null);
    }
  }

  @Transactional(readOnly = true)
  public MetaDtos.Session view(UUID id) {
    var session = owned(id);
    String status = session.getStatus();
    var accounts = new ArrayList<MetaDtos.Account>();
    if (!session.getExpiresAt().isAfter(now()) && !List.of("COMPLETED", "FAILED").contains(status))
      status = "EXPIRED";
    if ("READY".equals(status)) {
      for (var asset :
          cipher.decrypt(session.getOauthFlow(), session.getPayloadCiphertext()).assets()) {
        var channel =
            creators
                .findByPlatformAndExternalId(session.getPlatform(), asset.id())
                .filter(c -> Boolean.TRUE.equals(c.getIsStableId()))
                .orElse(null);
        String match =
            channel == null
                ? "UNMATCHED"
                : channel.getMaster() == null
                    ? "AVAILABLE"
                    : channel.getMaster().getUser().getId().equals(session.getUser().getId())
                        ? "ALREADY_LINKED"
                        : "UNAVAILABLE";
        if (channel != null
            && session.getTargetCreator() != null
            && !channel.getId().equals(session.getTargetCreator().getId())) match = "UNAVAILABLE";
        accounts.add(
            new MetaDtos.Account(
                asset.id(),
                asset.pageId(),
                asset.name(),
                asset.username(),
                channel == null ? null : channel.getId(),
                match));
      }
    }
    return new MetaDtos.Session(
        id,
        session.getPlatform(),
        status,
        session.getExpiresAt(),
        List.copyOf(accounts),
        session.getOauthFlow());
  }

  @Transactional(readOnly = true)
  public Selection selection(UUID id, String accountId) {
    var session = owned(id);
    requireConfigured(session.getOauthFlow());
    requireReady(session);
    var proof = cipher.decrypt(session.getOauthFlow(), session.getPayloadCiphertext());
    if (!proof.tokenExpiresAt().isAfter(now()))
      throw new ConflictException(
          ErrorCode.META_OAUTH_SESSION_EXPIRED, "Meta authorization expired");
    if (proof.assets().stream().noneMatch(a -> a.id().equals(accountId)))
      throw new ConflictException(
          ErrorCode.META_ACCOUNT_NOT_MATCHED, "Select an account from this Meta session");
    return new Selection(session.getPlatform(), proof, session.getOauthFlow());
  }

  @Transactional
  public int expire() {
    return sessions.expire(now());
  }

  public static String cookieName(UUID sessionId) {
    return "meta_oauth_" + sessionId.toString().replace("-", "");
  }
}
