package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.NotFoundException;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreatorChannelUnlinkService {
  private final ManualClaimGuard guard;
  private final CreatorRepository creators;
  private final JdbcTemplate db;
  private final EntityManager em;
  private final AuditService audit;
  private final Clock clock;

  @Transactional
  public void unlink(UUID creatorId) {
    var userId = Permissions.actor().getId();
    guard.users(userId, Permission.CLAIM_CREATOR_PROFILE);
    var creator = creators.findLockedById(creatorId)
        .orElseThrow(() -> new NotFoundException("Linked channel not found"));
    em.refresh(creator);
    if (creator.getMaster() == null || !userId.equals(creator.getMaster().getUser().getId()))
      throw new NotFoundException("Linked channel not found");

    if (hasOpenWork(creatorId))
      throw new ConflictException(ErrorCode.CHANNEL_UNLINK_BLOCKED,
          "Kênh đang có lời mời còn hiệu lực hoặc hợp tác chưa kết thúc.");

    db.update("""
        update creator_account_verification
        set status = case when status = 'ACTIVE' then 'REVOKED' else status end,
            access_token_encrypted = null, refresh_token_encrypted = null, token_expires_at = null
        where creator_id = ?
        """, creatorId);
    db.update("""
        update creator_claim set status = 'REVOKED', revoked_by_id = ?, revoked_at = ?,
            revoke_reason = 'Creator unlinked the channel'
        where creator_id = ? and claimant_id = ? and status = 'APPROVED'
        """, userId, LocalDateTime.now(clock), creatorId, userId);
    invalidateSessions(userId, creatorId, creator.getPlatform(), creator.getExternalId());

    UUID masterId = creator.getMaster().getId();
    creator.setMaster(null);
    creator.setSourceType(CreatorSourceType.PUBLIC);
    creator.setAuthorizedAt(null);
    guard.sync(creator);
    audit.record("UNLINK_CREATOR_CHANNEL", "Creator", creatorId,
        Map.of("masterId", masterId, "platform", creator.getPlatform()),
        Map.of("sourceType", "PUBLIC"), "Creator unlinked the channel");
  }

  private boolean hasOpenWork(UUID creatorId) {
    // Invitation and Deal writers lock the same channel before checking ownership.
    return Boolean.TRUE.equals(db.queryForObject("""
        select exists (
          select 1 from creator_invitation
          where target_creator_id = ? and status = 'PENDING' and expires_at > ?
          union all
          select 1 from cooperation_member m join cooperation_invitation i on i.deal_id = m.deal_id
          where m.creator_id = ? and m.active and i.status = 'PENDING' and i.expires_at > ?
          union all
          select 1 from collaboration c
          where c.status in ('PLANNED', 'IN_PROGRESS', 'AWAITING_REVIEW')
            and (c.creator_id = ? or exists (
              select 1 from collaboration_channel cc where cc.collaboration_id = c.id and cc.creator_id = ?
            ))
        )
        """, Boolean.class, creatorId, LocalDateTime.now(clock), creatorId,
        Timestamp.from(clock.instant()), creatorId, creatorId));
  }

  private void invalidateSessions(UUID userId, UUID creatorId, String platform, String externalId) {
    db.update("""
        update meta_oauth_session set status = 'EXPIRED', payload_ciphertext = null,
            confirmed_account_id = null, expires_at = least(expires_at, current_timestamp)
        where user_id = ? and platform = ? and (status in ('NEW', 'EXCHANGING', 'READY')
            or (status = 'COMPLETED' and confirmed_account_id = ?))
        """, userId, platform, externalId);
    if ("TIKTOK".equalsIgnoreCase(platform)) {
      db.update("""
          update tiktok_oauth_session set status = 'EXPIRED', open_id = null, username = null,
              display_name = null, match_status = null, failure_code = null,
              linked_creator_id = null, linked_master_id = null,
              expires_at = least(expires_at, current_timestamp)
          where user_id = ? and (status in ('NEW', 'EXCHANGING', 'READY')
              or (status = 'COMPLETED' and linked_creator_id = ?))
          """, userId, creatorId);
    }
  }
}
