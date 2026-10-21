package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.relationship.dto.AddShortlistItemRequest;
import com.influencermatch.backend.relationship.dto.ShortlistItemDto;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.influencermatch.backend.security.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShortlistServiceImpl implements ShortlistService {

    private final ShortlistItemRepository shortlistItemRepository;
    private final CampaignOwnershipService ownership;
    private final CreatorRepository creatorRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public ShortlistItemDto addCreatorToShortlist(UUID campaignId, AddShortlistItemRequest request) {
        Permissions.require(Permission.UPDATE_CAMPAIGN);
        Campaign campaign = ownership.requireOwned(campaignId);
        UUID userId = Permissions.actor().getId();
        if (shortlistItemRepository.existsByCampaignIdAndCreatorId(campaignId, request.getCreatorId())) {
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "Creator is already in the shortlist for this campaign");
        }

        Creator creator = creatorRepository.findById(request.getCreatorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Creator not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));

        ShortlistItem item = ShortlistItem.builder()
                .campaign(campaign)
                .creator(creator)
                .user(user)
                .priority(request.getPriority())
                .note(request.getNote())
                .addedAt(LocalDateTime.now())
                .build();

        item = shortlistItemRepository.save(item);
        return mapToDto(item);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ShortlistItemDto> getShortlistByCampaign(UUID campaignId, Pageable pageable) {
        ownership.requireOwned(campaignId);
        return shortlistItemRepository.findByCampaignIdAndUserId(campaignId, Permissions.actor().getId(), pageable)
                .map(this::mapToDto);
    }

    @Override
    @Transactional
    public void removeCreatorFromShortlist(UUID campaignId, UUID shortlistItemId) {
        Permissions.require(Permission.UPDATE_CAMPAIGN);
        ownership.requireOwned(campaignId);
        ShortlistItem item = shortlistItemRepository.findByIdAndCampaignIdAndUserId(shortlistItemId, campaignId, Permissions.actor().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Shortlist item not found or you don't have permission"));
        shortlistItemRepository.delete(item);
    }

    private ShortlistItemDto mapToDto(ShortlistItem item) {
        return ShortlistItemDto.builder()
                .id(item.getId())
                .campaignId(item.getCampaign().getId())
                .creatorId(item.getCreator().getId())
                .creatorName(item.getCreator().getDisplayName())
                .priority(item.getPriority())
                .note(item.getNote())
                .addedAt(item.getAddedAt())
                .build();
    }
}
