package com.influencermatch.backend.creator.meta;

import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorConflictAudit;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.repository.*;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies a freshly checked proof. Provider HTTP must finish before entering this transaction. */
@Service
@RequiredArgsConstructor
public class MetaOwnershipService {
  private final MetaOAuthSessionRepository sessions;
  private final MetaSessionStore store;
  private final MetaPayloadCipher cipher;
  private final UserRepository users;
  private final CreatorRepository creators;
  private final CreatorMasterRepository masters;
  private final CreatorInvitationRepository invitations;
  private final CreatorProviderIdentityRepository identities;
  private final CreatorAccountVerificationRepository verifications;
  private final CreatorIdentityAuditRepository audits;
  private final CreatorConflictAudit conflicts;
  private final ShortlistItemRepository shortlists;
  private final BrandCreatorRelationshipRepository relationships;
  @PersistenceContext private EntityManager em;

  @Transactional(noRollbackFor = CreatorOwnershipConflictException.class)
  public MetaDtos.Connected apply(UUID sessionId, String accountId, MetaDtos.Proof fresh) {
    var actor = store.actor();
    var initial =
        sessions
            .findById(sessionId)
            .filter(s -> s.getUser().getId().equals(actor.getId()))
            .orElseThrow(() -> new NotFoundException("Meta session not found"));
    var user =
        users
            .findLockedByEmail(actor.getEmail())
            .orElseThrow(() -> new ForbiddenException("Creator unavailable"));
    em.refresh(user);
    if (!user.getId().equals(actor.getId())) throw new ForbiddenException("Creator unavailable");
    store.assertUser(user, initial.getAuthVersion());
    creators.acquireIdentityLock(initial.getPlatform() + ":" + accountId);
    var creator =
        creators
            .findLockedByPlatformAndExternalId(initial.getPlatform(), accountId)
            .orElseThrow(() -> unmatched());
    em.refresh(creator);
    if (!Boolean.TRUE.equals(creator.getIsStableId())) throw unmatched();
    if (initial.getTargetCreator() != null
        && !initial.getTargetCreator().getId().equals(creator.getId())) throw unmatched();
    var pending =
        invitations.findByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByIdAsc(
            creator.getId(), "PENDING", LocalDateTime.ofInstant(store.now(), ZoneOffset.UTC));
    var lockIds = new TreeSet<UUID>();
    pending.forEach(i -> lockIds.add(i.getId()));
    if (initial.getInvitation() != null) lockIds.add(initial.getInvitation().getId());
    var lockedInvitations = new ArrayList<CreatorInvitation>();
    for (UUID id : lockIds) {
      var invitation =
          invitations
              .findLockedById(id)
              .orElseThrow(
                  () ->
                      new ConflictException(
                          ErrorCode.INVITATION_ALREADY_PROCESSED, "Invitation unavailable"));
      em.refresh(invitation);
      if (!invitation.getTargetCreator().getId().equals(creator.getId())) throw unmatched();
      store.checkInvitation(invitation, user);
      lockedInvitations.add(invitation);
    }
    var session = sessions.lockById(sessionId).orElseThrow();
    em.refresh(session);
    store.requireReady(session);
    if (!session.getUser().getId().equals(user.getId())
        || session.getAuthVersion() != user.getAuthVersion())
      throw new ForbiddenException("Meta session changed");
    var original = cipher.decrypt(session.getOauthFlow(), session.getPayloadCiphertext());
    if (!original.subject().equals(fresh.subject()) || !fresh.tokenExpiresAt().isAfter(store.now()))
      throw new ForbiddenException("Meta authorization changed");
    if (original.assets().stream().noneMatch(a -> a.id().equals(accountId))) throw unmatched();
    var asset =
        fresh.assets().stream()
            .filter(a -> a.id().equals(accountId))
            .findFirst()
            .orElseThrow(() -> unmatched());
    var master = masters.findByUserId(user.getId()).orElse(null);
    if (creator.getMaster() != null
        && (master == null || !creator.getMaster().getId().equals(master.getId())))
      throw conflicts.reject(
          creator.getId(),
          session.getInvitation() == null ? null : session.getInvitation().getId(),
          ErrorCode.CREATOR_ALREADY_LINKED,
          "Channel belongs to another Creator");
    if (creator.getMaster() == null && creator.getSourceType() == CreatorSourceType.AUTHORIZED)
      throw conflicts.reject(
          creator.getId(), null, ErrorCode.CREATOR_ALREADY_LINKED, "Channel already has ownership");
    var identity =
        identities.findByProviderAndProviderUserId(session.getPlatform(), accountId).orElse(null);
    if (identity != null && !identity.getCreator().getId().equals(creator.getId()))
      throw conflicts.reject(
          creator.getId(),
          null,
          ErrorCode.CREATOR_ALREADY_LINKED,
          "Channel identity is already linked");
    boolean alreadyOwned = creator.getMaster() != null;
    if (master == null)
      master =
          masters.save(
              CreatorMaster.builder()
                  .user(user)
                  .canonicalName(user.getFullName())
                  .status("ACTIVE")
                  .build());
    creator.setMaster(master);
    creator.setSourceType(CreatorSourceType.AUTHORIZED);
    LocalDateTime now = LocalDateTime.ofInstant(store.now(), ZoneOffset.UTC);
    if (!alreadyOwned || creator.getAuthorizedAt() == null) creator.setAuthorizedAt(now);
    var verification =
        verifications
            .findFirstByCreatorIdAndStatusOrderByVerifiedAtDesc(creator.getId(), "ACTIVE")
            .orElseGet(() -> CreatorAccountVerification.builder().creator(creator).build());
    for (var old : verifications.findByCreatorIdAndStatus(creator.getId(), "ACTIVE"))
      if (verification.getId() == null || !old.getId().equals(verification.getId()))
        old.setStatus("REVOKED");
    verification.setVerifiedByUser(user);
    verification.setVerificationMethod(
        session.getOauthFlow() == OAuthFlow.INSTAGRAM_LOGIN ? "INSTAGRAM_OAUTH" : "META_OAUTH");
    verification.setStatus("ACTIVE");
    verification.setVerifiedAt(now);
    verification.setGrantedScopes(String.join(",", new TreeSet<>(fresh.scopes())));
    verification.setAccessTokenEncrypted(null);
    verification.setRefreshTokenEncrypted(null);
    verification.setTokenExpiresAt(null);
    verifications.save(verification);
    if (identity == null)
      identity =
          CreatorProviderIdentity.builder()
              .creator(creator)
              .provider(session.getPlatform())
              .providerUserId(accountId)
              .build();
    identity.setProviderUsername(asset.username());
    var metadata = new HashMap<String, Object>();
    metadata.put("id", asset.id());
    if (asset.pageId() != null) metadata.put("pageId", asset.pageId());
    metadata.put("name", asset.name());
    metadata.put("username", asset.username());
    metadata.put("oauthFlow", session.getOauthFlow().name());
    identity.setRawProfileData(metadata);
    identities.save(identity);
    lockedInvitations.forEach(i -> i.setStatus("ACCEPTED"));
    shortlists.updateMasterForCreator(creator.getId(), master);
    relationships.updateMasterForCreator(creator.getId(), master);
    audits.save(
        CreatorIdentityAudit.builder()
            .eventType(alreadyOwned ? "REFRESH_META_VERIFICATION" : "LINK_META_ACCOUNT")
            .master(master)
            .account(creator)
            .performedBy(user)
            .details(
                Map.of(
                    "oauthFlow",
                    session.getOauthFlow().name(),
                    "platform",
                    session.getPlatform(),
                    "accountId",
                    accountId,
                    "sessionId",
                    sessionId.toString()))
            .build());
    session.setStatus("COMPLETED");
    session.setConfirmedAccountId(accountId);
    session.setPayloadCiphertext(null);
    em.flush();
    return new MetaDtos.Connected(sessionId, creator.getId(), master.getId(), "AUTHORIZED");
  }

  private ConflictException unmatched() {
    return new ConflictException(
        ErrorCode.META_ACCOUNT_NOT_MATCHED,
        "Official Meta account does not match an existing stable channel; use manual Claim");
  }
}
