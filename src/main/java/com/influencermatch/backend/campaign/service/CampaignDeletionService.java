package com.influencermatch.backend.campaign.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignDeletionService {
  private final CampaignWriteLock locks;
  private final CampaignRepository campaigns;
  private final JdbcTemplate db;
  private final AuditService audit;

  @Transactional
  @PreAuthorize("hasAuthority('CHANGE_CAMPAIGN_STATUS')")
  public void deleteDraft(UUID id, String revision) {
    Permissions.require(Permission.CHANGE_CAMPAIGN_STATUS);
    var campaign = locks.owned(id);
    CampaignRevision.require(revision, id, campaign.getCampaignRevision());
    if (campaign.getStatus() != CampaignStatus.DRAFT) throw blocked();
    // Never cascade away business history or immutable recommendation/copy receipts.
    Boolean used = db.queryForObject("""
        select exists(select 1 from relationship where campaign_id=?)
          or exists(select 1 from collaboration where campaign_id=?)
          or exists(select 1 from cooperation_deal where campaign_id=?)
          or exists(select 1 from creator_invitation where campaign_id=?)
          or exists(select 1 from recommendation_run where campaign_id=?)
          or exists(select 1 from ai_recommendation_request where campaign_id=?)
          or exists(select 1 from campaign_context where campaign_id=?)
          or exists(select 1 from campaign_duplicate_request
                    where source_campaign_id=? or target_campaign_id=?)
        """, Boolean.class, id, id, id, id, id, id, id, id, id);
    if (!Boolean.FALSE.equals(used)) throw blocked();
    try {
      db.update("delete from shortlist_item where campaign_id=?", id);
      campaigns.delete(campaign);
      campaigns.flush();
    } catch (DataIntegrityViolationException e) {
      // Concurrent/new foreign-key references must roll back the entire deletion.
      throw new BusinessException(ErrorCode.CAMPAIGN_DELETE_BLOCKED,
          "Campaign has linked data. Archive it instead.", e);
    }
    audit.record("DELETE_DRAFT_CAMPAIGN", "CAMPAIGN", id,
        Map.of("name", campaign.getName(), "status", "DRAFT", "revision", campaign.getCampaignRevision()),
        null, "Brand permanently deleted an unused draft campaign");
  }

  private BusinessException blocked() {
    return new BusinessException(ErrorCode.CAMPAIGN_DELETE_BLOCKED,
        "Only unused DRAFT campaigns can be permanently deleted. Archive campaigns with linked history instead.");
  }
}
