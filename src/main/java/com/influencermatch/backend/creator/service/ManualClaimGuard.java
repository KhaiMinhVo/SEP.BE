package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.portal.CreatorPortalProjection;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.repository.*;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Database-only checks; called inside the Claim transaction before taking channel locks. */
@Component
@RequiredArgsConstructor
public class ManualClaimGuard {
  final JdbcTemplate db;
  final CreatorRepository creators;
  final CreatorInvitationRepository invitations;
  final ShortlistItemRepository shortlists;
  final BrandCreatorRelationshipRepository relationships;
  final CreatorPortalProjection projection;
  final EntityManager em;
  final Clock clock;
  final com.influencermatch.backend.auth.service.AuthSessionStore sessions;

  public void users(UUID claimant, Permission permission) {
    var actor = Permissions.actor();
    var ids = new TreeSet<UUID>(List.of(claimant, actor.getId()));
    for (var id : ids) db.queryForList("select id from \"user\" where id=? for update", id);
    read(permission);
  }

  public void read(Permission permission) {
    Permissions.require(permission);
    var actor = Permissions.actor();
    var u =
        db.queryForMap(
            "select role,status,auth_version,email_verified from \"user\" where id=?",
            actor.getId());
    boolean reviewer = permission == Permission.REVIEW_CREATOR_CLAIM;
    if (!"ACTIVE".equals(u.get("status"))
        || ((Number) u.get("auth_version")).longValue() != actor.getAuthVersion()
        || !(reviewer
            ? Set.of("ADMIN", "DATA_MANAGER").contains(u.get("role"))
            : "CREATOR".equals(u.get("role"))))
      throw new ForbiddenException("Claim authorization changed");
    if (!reviewer && !Boolean.TRUE.equals(u.get("email_verified")))
      throw new ForbiddenException("Verified email required");
    if (db.queryForObject(
            "select count(*) from role_permission_override where role=? and permission=? and not"
                + " enabled",
            Long.class,
            u.get("role"),
            permission.name())
        > 0) throw new ForbiddenException("Permission disabled");
    sessions.require(sessions.currentId(), actor, false);
  }

  public void identity(UUID creator) {
    var rows = db.queryForList("select platform,external_id from creator where id=?", creator);
    if (rows.isEmpty()) return;
    var r = rows.getFirst();
    if ("TIKTOK".equals(r.get("platform")))
      creators.acquireIdentityLock(
          com.influencermatch.backend.creator.tiktok.TikTokUsernames.MATCH_LOCK);
    creators.acquireIdentityLock(r.get("platform") + ":" + r.get("external_id"));
  }

  public List<CreatorInvitation> invitations(Creator creator, User claimant, CreatorClaim claim) {
    var now = LocalDateTime.now(clock);
    var ids = new TreeSet<UUID>();
    invitations
        .findByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByIdAsc(
            creator.getId(), "PENDING", now)
        .forEach(i -> ids.add(i.getId()));
    if (claim != null && claim.getInvitation() != null) ids.add(claim.getInvitation().getId());
    var out = new ArrayList<CreatorInvitation>();
    for (var id : ids) {
      var i =
          invitations
              .findLockedById(id)
              .orElseThrow(
                  () ->
                      new ConflictException(
                          ErrorCode.INVITATION_ALREADY_PROCESSED, "Invitation unavailable"));
      em.refresh(i);
      if (!"PENDING".equals(i.getStatus()))
        throw new ConflictException(
            ErrorCode.INVITATION_ALREADY_PROCESSED, "Invitation already processed");
      if (!i.getExpiresAt().isAfter(LocalDateTime.now(clock)))
        throw new ConflictException(ErrorCode.INVITATION_EXPIRED, "Invitation expired");
      if (!i.getTargetCreator().getId().equals(creator.getId())
          || !i.getInvitedEmail().equalsIgnoreCase(claimant.getEmail()))
        throw new ForbiddenException("Invitation belongs to another Creator");
      out.add(i);
    }
    return out;
  }

  public void claim(UUID id) {
    db.queryForList("select id from creator_claim where id=? for update", id);
  }

  public void sync(Creator creator) {
    em.flush();
    shortlists.updateMasterForCreator(creator.getId(), creator.getMaster());
    relationships.updateMasterForCreator(creator.getId(), creator.getMaster());
  }

  public boolean remainingProof(UUID creator) {
    em.flush();
    var p = projection.project(List.of(creator), false).get(creator);
    return p != null && p.verification().verified();
  }
}
