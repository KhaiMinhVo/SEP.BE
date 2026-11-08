package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.campaign.service.CampaignWriteLock;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.enums.*;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.permission.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class ShortlistItemWriter {
  private final CampaignOwnershipService ownership;
  private final CampaignWriteLock locks;
  private final ShortlistItemRepository items;
  private final CreatorRepository creators;
  private final RecommendationRunRepository runs;
  private final ShortlistProjectionService projection;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  @Transactional(readOnly = true)
  public void preflight(UUID id) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(id);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ShortlistItemDto add(UUID campaignId, AddShortlistItemRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);
    var campaign = locks.owned(campaignId);
    var actor = Permissions.actor();
    if (request == null || request.getEffectiveCreatorId() == null)
      throw new ValidationException("Creator ID is required");
    if (request.getCreatorId() != null
        && request.getCreatorAccountId() != null
        && !request.getCreatorId().equals(request.getCreatorAccountId()))
      throw new ValidationException("Creator aliases must match");
    var id = request.getEffectiveCreatorId();
    var item = items.findLockedByCampaignIdAndCreatorId(campaignId, id).orElse(null);
    var creator =
        item == null
            ? creators.findById(id).orElseThrow(() -> new NotFoundException("Creator not found"))
            : item.getCreator();
    com.influencermatch.backend.campaign.model.RecommendationRun run = null;
    if (request.getRecommendationRunId() != null) {
      run =
          runs.findById(request.getRecommendationRunId())
              .filter(
                  r ->
                      r.getCampaign().getId().equals(campaignId)
                          && r.getBrandProfile().getUser().getId().equals(actor.getId()))
              .orElseThrow(
                  () -> new ValidationException("Recommendation run does not belong to Campaign"));
    }
    String action;
    if (item != null) {
      if (item.getUser() == null || !item.getUser().getId().equals(actor.getId()))
        throw new NotFoundException("Shortlist item not found");
      if (item.getStatus() != ShortlistStatus.REMOVED) {
        action = "ALREADY_EXISTS";
      } else {
        item.setStatus(ShortlistStatus.SAVED);
        if (request.getPriority() != null) item.setPriority(request.getPriority());
        if (request.getNote() != null) item.setNote(request.getNote());
        items.saveAndFlush(item);
        action = "RESTORED";
      }
    } else {
      item =
          items.saveAndFlush(
              ShortlistItem.builder()
                  .campaign(campaign)
                  .creator(creator)
                  .master(creator.getMaster())
                  .user(campaign.getBrandProfile().getUser())
                  .priority(
                      request.getPriority() == null
                          ? ShortlistPriority.MEDIUM
                          : request.getPriority())
                  .note(request.getNote())
                  .source(
                      request.getSource() == null
                          ? ShortlistSource.MANUAL_DISCOVERY
                          : request.getSource())
                  .recommendationRun(run)
                  .status(ShortlistStatus.SAVED)
                  .addedAt(
                      LocalDateTime.ofInstant(clock.instant(), java.time.ZoneId.systemDefault()))
                  .build());
      action = "ADDED";
    }
    var dto = projection.one(item);
    dto.setAction(action);
    dto.setMessage(
        action.equals("ALREADY_EXISTS")
            ? "Ứng viên đã có sẵn trong Shortlist"
            : (action.equals("RESTORED") ? "Restored " : "Added ")
                + (creator.getDisplayName() != null
                    ? creator.getDisplayName()
                    : creator.getUserName())
                + " to Shortlist");
    dto.setTotalShortlistCount(
        items.countByCampaignIdAndStatusNot(campaignId, ShortlistStatus.REMOVED));
    if (!action.equals("ALREADY_EXISTS")) {
      events.publishEvent(
          new ShortlistRefreshEvents.Requested(
              campaignId,
              id,
              creator.getPlatform(),
              creator.getUserName(),
              creator.getDisplayName()));
      dto.setRefreshRequestStatus("PENDING_DISPATCH");
    }
    return dto;
  }
}
