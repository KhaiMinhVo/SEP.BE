package com.influencermatch.backend.creator.tiktok;

import com.influencermatch.backend.creator.client.provider.TikTokSandboxProofProvider.VerifiedAccount;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorConflictAudit;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.repository.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Fresh TikTok proof is applied here; no provider HTTP or token storage in this transaction. */
@Service
@RequiredArgsConstructor
public class TikTokOwnershipService {
  private final TikTokOAuthSessionRepository sessions;
  private final TikTokSessionStore store;
  private final TikTokAccountBindingRepository bindings;
  private final UserRepository users;
  private final CreatorRepository creators;
  private final CreatorMasterRepository masters;
  private final CreatorInvitationRepository invitations;
  private final CreatorAccountVerificationRepository verifications;
  private final CreatorIdentityAuditRepository audits;
  private final CreatorConflictAudit conflicts;
  private final ShortlistItemRepository shortlists;
  private final BrandCreatorRelationshipRepository relationships;
  private final Clock clock;
  private final TikTokIdentityResolver resolver;
  @PersistenceContext private EntityManager em;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public TikTokSessionStore.Result<TikTokDtos.Callback> apply(
      UUID id, VerifiedAccount proof, TikTokIdentityResolver.Identity identity) {
    var initial =
        sessions.findById(id).orElseThrow(() -> new NotFoundException("TikTok session not found"));
    var user =
        users
            .findLockedByEmail(initial.getUser().getEmail())
            .orElseThrow(() -> new ForbiddenException("Creator unavailable"));
    if (!user.getId().equals(initial.getUser().getId()))
      throw new ForbiddenException("Creator unavailable");
    creators.acquireIdentityLock(TikTokUsernames.MATCH_LOCK);
    var identityKeys = new TreeSet<String>();
    identityKeys.add("TIKTOK:SANDBOX:" + initial.getClientKey() + ":" + proof.openId());
    identityKeys.add("TIKTOK:" + identity.authorId());
    identityKeys.forEach(creators::acquireIdentityLock);
    String username = identity.username();
    Creator creator =
        creators.findLockedByPlatformAndExternalId("TIKTOK", identity.authorId()).orElse(null);
    if (creator != null) em.refresh(creator);
    var lockedInvitations = new ArrayList<CreatorInvitation>();
    var invitationIds = new TreeSet<UUID>();
    if (creator != null
        && (initial.getTargetCreator() == null
            || initial.getTargetCreator().getId().equals(creator.getId())))
      invitations
          .findByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByIdAsc(
              creator.getId(), "PENDING", localNow())
          .forEach(i -> invitationIds.add(i.getId()));
    if (initial.getInvitation() != null) invitationIds.add(initial.getInvitation().getId());
    for (var invitationId : invitationIds) {
      var invitation =
          invitations
              .findLockedById(invitationId)
              .orElseThrow(() -> new ValidationException("Invitation unavailable"));
      em.refresh(invitation);
      lockedInvitations.add(invitation);
    }
    var session = sessions.lockById(id).orElseThrow();
    em.refresh(session);
    if (!"COMPLETED".equals(session.getStatus())
        && !session.getExpiresAt().isAfter(clock.instant())) {
      session.setStatus("EXPIRED");
      clearProfile(session);
      return new TikTokSessionStore.Result<>(
          null,
          new BusinessException(
              ErrorCode.TIKTOK_SESSION_EXPIRED, "TikTok session expired; start again"));
    }
    if (!"EXCHANGING".equals(session.getStatus()))
      throw new BusinessException(
          ErrorCode.TIKTOK_ALREADY_PROCESSED, "TikTok callback already processed");
    String match =
        session.getTargetCreator() != null
                && (creator == null || !session.getTargetCreator().getId().equals(creator.getId()))
            ? "TARGET_MISMATCH"
            : "MATCHED";
    // Only guard failures are converted to committed failure results; mutation/audit errors roll
    // back.
    try {
      if (!session.getExpiresAt().isAfter(clock.instant()))
        throw new BusinessException(
            ErrorCode.TIKTOK_SESSION_EXPIRED, "TikTok session expired; start again");
      resolver.requireFresh(identity);
      store.assertUser(user, session.getAuthVersion());
      store.assertSession(session);
      if (username.isEmpty())
        throw new BusinessException(ErrorCode.TIKTOK_PROVIDER_ERROR, "TikTok username is required");
      for (var invitation : lockedInvitations) store.checkInvitation(invitation, user);
      if ("MATCHED".equals(match)
          && creator != null
          && creator.getMaster() != null
          && !creator.getMaster().getUser().getId().equals(user.getId()))
        throw rejection(user, creator, session, "Channel belongs to another Creator");
      if ("MATCHED".equals(match)
          && creator != null
          && creator.getMaster() == null
          && creator.getSourceType() == CreatorSourceType.AUTHORIZED)
        throw rejection(user, creator, session, "Channel already has ownership");
      if ("MATCHED".equals(match)
          && creator != null
          && !Boolean.TRUE.equals(creator.getIsStableId()))
        throw rejection(user, creator, session, "Stored channel identity is not stable");
    } catch (BusinessException ex) {
      session.setStatus(ex.code() == ErrorCode.TIKTOK_SESSION_EXPIRED ? "EXPIRED" : "FAILED");
      clearProfile(session);
      return new TikTokSessionStore.Result<>(null, ex);
    }
    if (!"MATCHED".equals(match)) {
      session.setStatus("READY");
      session.setOpenId(proof.openId());
      session.setUsername(proof.username());
      session.setDisplayName(proof.displayName());
      session.setMatchStatus(match);
      return new TikTokSessionStore.Result<>(new TikTokDtos.Callback(id, "READY"), null);
    }
    var binding =
        bindings
            .findByEnvironmentAndClientKeyAndOpenId(
                "SANDBOX", session.getClientKey(), proof.openId())
            .orElse(null);
    var existingChannelBinding =
        creator == null
            ? null
            : bindings
                .findByEnvironmentAndClientKeyAndCreatorId(
                    "SANDBOX", session.getClientKey(), creator.getId())
                .orElse(null);
    if (binding != null
            && (creator == null || !binding.getCreator().getId().equals(creator.getId()))
        || existingChannelBinding != null
            && !existingChannelBinding.getOpenId().equals(proof.openId())) {
      var rejected =
          rejection(
              user,
              creator != null ? creator : binding.getCreator(),
              session,
              "TikTok identity is already linked");
      session.setStatus("FAILED");
      clearProfile(session);
      return new TikTokSessionStore.Result<>(null, rejected);
    }
    if (creator == null) {
      creator =
          creators.saveAndFlush(
              Creator.builder()
                  .platform("TIKTOK")
                  .externalId(identity.authorId())
                  .userName(username)
                  .isStableId(true)
                  .sourceType(CreatorSourceType.PUBLIC)
                  .build());
    }
    creator.setUserName(username);
    creator.setProfileUrl("https://www.tiktok.com/@" + username);
    if (identity.displayName() != null) creator.setDisplayName(identity.displayName());
    if (identity.avatarUrl() != null) creator.setAvatarUrl(identity.avatarUrl());
    boolean alreadyOwned = creator.getMaster() != null;
    var master = masters.findByUserId(user.getId()).orElse(null);
    if (master == null)
      master =
          masters.save(
              CreatorMaster.builder()
                  .user(user)
                  .canonicalName(user.getFullName() == null ? "Creator" : user.getFullName())
                  .status("ACTIVE")
                  .build());
    creator.setMaster(master);
    creator.setSourceType(CreatorSourceType.AUTHORIZED);
    if (!alreadyOwned || creator.getAuthorizedAt() == null) creator.setAuthorizedAt(localNow());
    var verification =
        verifications
            .findFirstByCreatorIdAndStatusOrderByVerifiedAtDesc(creator.getId(), "ACTIVE")
            .orElseGet(() -> CreatorAccountVerification.builder().build());
    for (var old : verifications.findByCreatorIdAndStatus(creator.getId(), "ACTIVE"))
      if (verification.getId() == null || !old.getId().equals(verification.getId()))
        old.setStatus("REVOKED");
    verification.setCreator(creator);
    verification.setVerifiedByUser(user);
    verification.setVerificationMethod("TIKTOK_OAUTH_SANDBOX");
    verification.setStatus("ACTIVE");
    verification.setVerifiedAt(localNow());
    verification.setGrantedScopes("user.info.basic,user.info.profile");
    verification.setAccessTokenEncrypted(null);
    verification.setRefreshTokenEncrypted(null);
    verification.setTokenExpiresAt(null);
    verifications.save(verification);
    if (binding == null)
      binding =
          TikTokAccountBinding.builder()
              .environment("SANDBOX")
              .clientKey(session.getClientKey())
              .openId(proof.openId())
              .creator(creator)
              .build();
    binding.setUsername(username);
    binding.setVerifiedAt(clock.instant());
    bindings.save(binding);
    lockedInvitations.forEach(i -> i.setStatus("ACCEPTED"));
    shortlists.updateMasterForCreator(creator.getId(), master);
    relationships.updateMasterForCreator(creator.getId(), master);
    audits.save(
        CreatorIdentityAudit.builder()
            .eventType(alreadyOwned ? "REFRESH_TIKTOK_VERIFICATION" : "LINK_TIKTOK_ACCOUNT")
            .account(creator)
            .master(master)
            .performedBy(user)
            .details(
                Map.of(
                    "matchMethod",
                    "CRAWLER_AUTHOR_ID",
                    "authorId",
                    identity.authorId(),
                    "observedAt",
                    identity.observedAt().toString(),
                    "environment",
                    "SANDBOX",
                    "clientKey",
                    session.getClientKey(),
                    "sessionId",
                    id.toString()))
            .build());
    session.setStatus("COMPLETED");
    session.setMatchStatus("MATCHED");
    session.setLinkedCreatorId(creator.getId());
    session.setLinkedMasterId(master.getId());
    clearProfile(session);
    em.flush();
    return new TikTokSessionStore.Result<>(
        new TikTokDtos.Callback(id, "AUTHORIZED", creator.getId(), master.getId()), null);
  }

  private CreatorOwnershipConflictException rejection(
      User user, Creator creator, TikTokOAuthSession session, String reason) {
    return conflicts.rejectForActor(
        user.getId(),
        creator.getId(),
        session.getInvitation() == null ? null : session.getInvitation().getId(),
        ErrorCode.CREATOR_ALREADY_LINKED,
        reason);
  }

  private LocalDateTime localNow() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private void clearProfile(TikTokOAuthSession session) {
    session.setOpenId(null);
    session.setUsername(null);
    session.setDisplayName(null);
  }
}
