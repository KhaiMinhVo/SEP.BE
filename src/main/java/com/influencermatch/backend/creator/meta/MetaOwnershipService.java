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
    store.lockCooperationCampaign(initial);
    creators.acquireIdentityLock(initial.getPlatform() + ":" + accountId);
    var existing =
        creators
            .findLockedByPlatformAndExternalId(initial.getPlatform(), accountId)
            .orElse(null);
    if (existing == null && !MetaSessionStore.isStandalone(initial)) throw unmatched();
    if (existing != null) {
      em.refresh(existing);
      if (!Boolean.TRUE.equals(existing.getIsStableId())) throw unmatched();
    }
    if (initial.getTargetCreator() != null
        && !initial.getTargetCreator().getId().equals(existing.getId())) throw unmatched();
    var lockIds = new TreeSet<UUID>();
    if (existing != null)
      invitations.findByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByIdAsc(
          existing.getId(), "PENDING", LocalDateTime.ofInstant(store.now(), ZoneOffset.UTC))
          .forEach(i -> lockIds.add(i.getId()));
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
      if (!invitation.getTargetCreator().getId().equals(existing.getId())) throw unmatched();
      store.checkInvitation(invitation, user);
      lockedInvitations.add(invitation);
    }
    var session = sessions.lockById(sessionId).orElseThrow();
    em.refresh(session);
    store.requireReady(session);
    store.assertCooperationTarget(session);
    if (!session.getUser().getId().equals(user.getId())
        || session.getAuthVersion() != user.getAuthVersion())
      throw new ForbiddenException("Meta session changed");
    var original = cipher.decrypt(session.getOauthFlow(), session.getPayloadCiphertext());
    if (!original.subject().equals(fresh.subject()) || !fresh.tokenExpiresAt().isAfter(store.now()))
      throw new ForbiddenException("Meta authorization changed");
    var originalAsset =
        original.assets().stream().filter(a -> a.id().equals(accountId)).findFirst()
            .orElseThrow(() -> unmatched());
    var asset =
        fresh.assets().stream()
            .filter(a -> a.id().equals(accountId))
            .findFirst()
            .orElseThrow(() -> unmatched());
    if (!MetaSessionStore.hasAssetAccess(session.getPlatform(), asset)) throw unmatched();
    if (existing == null && (!MetaSessionStore.isStandalone(session)
        || !MetaSessionStore.isCanonicalAssetId(asset.id())
        || store.hasLegacyFacebookChannel(session.getPlatform(), asset)
        || store.hasLegacyFacebookChannel(session.getPlatform(), originalAsset))) throw unmatched();
    var master = masters.findByUserId(user.getId()).orElse(null);
    if (existing != null && existing.getMaster() != null
        && (master == null || !existing.getMaster().getId().equals(master.getId())))
      throw conflicts.reject(
          existing.getId(),
          session.getInvitation() == null ? null : session.getInvitation().getId(),
          ErrorCode.CREATOR_ALREADY_LINKED,
          "Channel belongs to another Creator");
    if (existing != null && existing.getMaster() == null
        && existing.getSourceType() == CreatorSourceType.AUTHORIZED)
      throw conflicts.reject(
          existing.getId(), null, ErrorCode.CREATOR_ALREADY_LINKED, "Channel already has ownership");
    var identity =
        identities.findByProviderAndProviderUserId(session.getPlatform(), accountId).orElse(null);
    if (identity != null && (existing == null || !identity.getCreator().getId().equals(existing.getId())))
      throw conflicts.reject(
          existing == null ? identity.getCreator().getId() : existing.getId(),
          null,
          ErrorCode.CREATOR_ALREADY_LINKED,
          "Channel identity is already linked");
    boolean alreadyOwned = existing != null && existing.getMaster() != null;
    if (master == null)
      master =
          masters.save(
              CreatorMaster.builder()
                  .user(user)
                  .canonicalName(user.getFullName())
                  .status("ACTIVE")
                  .build());
    LocalDateTime now = LocalDateTime.ofInstant(store.now(), ZoneOffset.UTC);
    var creator = existing == null ? newChannel(session.getPlatform(), asset) : existing;
    creator.setMaster(master);
    creator.setSourceType(CreatorSourceType.AUTHORIZED);
    if (!alreadyOwned || creator.getAuthorizedAt() == null) creator.setAuthorizedAt(now);
    if (existing == null) creators.save(creator);
    var verification =
        verifications
            .findFirstByCreatorIdAndStatusOrderByVerifiedAtDesc(creator.getId(), "ACTIVE")
            .orElseGet(() -> CreatorAccountVerification.builder().creator(creator).build());
    for (var old : verifications.findByCreatorIdAndStatus(creator.getId(), "ACTIVE"))
      if (verification.getId() == null || !old.getId().equals(verification.getId()))
        old.setStatus("REVOKED");
    verification.setVerifiedByUser(user);
    verification.setVerificationMethod(
        switch (session.getOauthFlow()) {
          case INSTAGRAM_LOGIN -> "INSTAGRAM_OAUTH";
          case INSTAGRAM_UNIFIED_LOGIN -> "INSTAGRAM_UNIFIED_OAUTH";
          case FACEBOOK_LOGIN -> "META_OAUTH";
        });
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

  private static Creator newChannel(String platform, MetaDtos.Asset asset) {
    String username = profileText(asset.username());
    String profileUrl = "FACEBOOK".equals(platform) ? "https://www.facebook.com/" + asset.id()
        : username != null && username.matches("[a-zA-Z0-9._]+")
            ? "https://www.instagram.com/" + username + "/" : null;
    return Creator.builder()
        .platform(platform)
        .externalId(asset.id())
        .isStableId(true)
        .userName(username == null ? asset.id() : username)
        .displayName(profileText(asset.name()))
        .profileUrl(profileUrl)
        .build();
  }

  private static String profileText(String text) {
    if (text == null) return null;
    String value = text.trim();
    return value.isEmpty() || value.length() > 255 ? null : value;
  }

  private ConflictException unmatched() {
    return new ConflictException(
        ErrorCode.META_ACCOUNT_NOT_MATCHED,
        "The selected Meta account does not match an eligible channel");
  }
}
