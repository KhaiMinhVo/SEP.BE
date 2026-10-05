package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.relationship.dto.AddShortlistItemRequest;
import com.influencermatch.backend.relationship.dto.ShortlistItemDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ShortlistService {
    ShortlistItemDto addCreatorToShortlist(UUID campaignId, AddShortlistItemRequest request, UUID userId);
    Page<ShortlistItemDto> getShortlistByCampaign(UUID campaignId, Pageable pageable);
    void removeCreatorFromShortlist(UUID shortlistItemId, UUID userId);
}
