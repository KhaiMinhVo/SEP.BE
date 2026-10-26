package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ShortlistService {
  ShortlistItemDto addCreatorToShortlist(UUID campaignId, AddShortlistItemRequest request);

  Page<ShortlistItemDto> getShortlistByCampaign(UUID campaignId, Pageable pageable);

  Page<ShortlistItemDto> getShortlistByCampaign(
      UUID campaignId,
      ShortlistStatus status,
      ShortlistPriority priority,
      String platform,
      Pageable pageable);

  ShortlistItemDto updateShortlistItem(
      UUID campaignId, UUID shortlistItemId, UpdateShortlistItemRequest request);

  List<ShortlistItemDto> batchUpdateStatus(UUID campaignId, BatchUpdateStatusRequest request);

  void removeCreatorFromShortlist(UUID campaignId, UUID shortlistItemId);

  ContactReadinessResponse checkContactReadiness(
      UUID campaignId, ContactReadinessRequest request);

  ShortlistOutreachResponse outreachShortlist(
      UUID campaignId, ShortlistOutreachRequest request);
}

