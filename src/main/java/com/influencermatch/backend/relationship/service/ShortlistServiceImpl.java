package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.RecommendationRun;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.relationship.dto.AddShortlistItemRequest;
import com.influencermatch.backend.relationship.dto.ShortlistItemDto;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistSource;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShortlistServiceImpl implements ShortlistService {

    private final ShortlistItemRepository shortlistItemRepository;
    private final CampaignOwnershipService ownership;
    private final CreatorRepository creatorRepository;
    private final UserRepository userRepository;
    private final RecommendationRunRepository recommendationRunRepository;

    @Override
    @Transactional
    public ShortlistItemDto addCreatorToShortlist(UUID campaignId, AddShortlistItemRequest request) {
        Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
        Campaign campaign = ownership.requireOwned(campaignId);
        UUID userId = Permissions.actor().getId();

        ShortlistPriority priority = request.getPriority() != null ? request.getPriority() : ShortlistPriority.MEDIUM;
        ShortlistSource source = request.getSource() != null ? request.getSource() : ShortlistSource.MANUAL_DISCOVERY;

        // Idempotent Shortlist Cart Check (Chống lỗi 409)
        Optional<ShortlistItem> existingOpt = shortlistItemRepository.findByCampaignIdAndCreatorId(campaignId, request.getCreatorId());
        if (existingOpt.isPresent()) {
            ShortlistItem existing = existingOpt.get();
            // Nếu trước đó bị REMOVED, phục hồi về SAVED
            if (existing.getStatus() == ShortlistStatus.REMOVED) {
                existing.setStatus(ShortlistStatus.SAVED);
                if (request.getNote() != null) {
                    existing.setNote(request.getNote());
                }
                if (request.getPriority() != null) {
                    existing.setPriority(request.getPriority());
                }
                existing = shortlistItemRepository.save(existing);
            } else if (request.getPriority() != null) {
                existing.setPriority(request.getPriority());
                existing = shortlistItemRepository.save(existing);
            }

            long totalCount = shortlistItemRepository.countByCampaignIdAndStatusNot(campaignId, ShortlistStatus.REMOVED);

            ShortlistItemDto dto = mapToDto(existing);
            dto.setAction("ALREADY_EXISTS");
            dto.setIsInShortlist(true);
            dto.setTotalShortlistCount(totalCount);
            dto.setMessage("Ứng viên đã có sẵn trong Shortlist");
            return dto;
        }

        Creator creator = creatorRepository.findById(request.getCreatorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Creator not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));

        RecommendationRun run = null;
        if (request.getRecommendationRunId() != null) {
            run = recommendationRunRepository.findById(request.getRecommendationRunId()).orElse(null);
        }

        ShortlistItem item = ShortlistItem.builder()
                .campaign(campaign)
                .creator(creator)
                .master(creator.getMaster())
                .user(user)
                .priority(priority)
                .status(ShortlistStatus.SAVED)
                .source(source)
                .recommendationRun(run)
                .note(request.getNote())
                .addedAt(LocalDateTime.now())
                .build();

        item = shortlistItemRepository.save(item);

        long totalCount = shortlistItemRepository.countByCampaignIdAndStatusNot(campaignId, ShortlistStatus.REMOVED);
        String creatorName = creator.getDisplayName() != null ? creator.getDisplayName() : creator.getUserName();

        ShortlistItemDto dto = mapToDto(item);
        dto.setAction("ADDED");
        dto.setIsInShortlist(true);
        dto.setTotalShortlistCount(totalCount);
        dto.setMessage("Đã thêm " + creatorName + " vào Shortlist");
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ShortlistItemDto> getShortlistByCampaign(UUID campaignId, Pageable pageable) {
        Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
        ownership.requireOwned(campaignId);
        return shortlistItemRepository.findByCampaignIdAndUserId(campaignId, Permissions.actor().getId(), pageable)
                .map(this::mapToDto);
    }

    @Override
    @Transactional
    public void removeCreatorFromShortlist(UUID campaignId, UUID shortlistItemId) {
        Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
        ownership.requireOwned(campaignId);
        ShortlistItem item = shortlistItemRepository.findByIdAndCampaignIdAndUserId(shortlistItemId, campaignId, Permissions.actor().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Shortlist item not found or you don't have permission"));
        // Đổi trạng thái sang REMOVED để bảo toàn lịch sử CRM
        item.setStatus(ShortlistStatus.REMOVED);
        shortlistItemRepository.save(item);
    }

    private ShortlistItemDto mapToDto(ShortlistItem item) {
        UUID masterId = null;
        if (item.getMaster() != null) {
            masterId = item.getMaster().getId();
        } else if (item.getCreator() != null && item.getCreator().getMaster() != null) {
            masterId = item.getCreator().getMaster().getId();
        }

        String creatorName = null;
        if (item.getCreator() != null) {
            creatorName = item.getCreator().getDisplayName() != null 
                ? item.getCreator().getDisplayName() 
                : item.getCreator().getUserName();
        }

        return ShortlistItemDto.builder()
                .id(item.getId())
                .campaignId(item.getCampaign().getId())
                .creatorId(item.getCreator().getId())
                .masterId(masterId)
                .creatorName(creatorName)
                .priority(item.getPriority())
                .status(item.getStatus())
                .source(item.getSource())
                .recommendationRunId(item.getRecommendationRun() != null ? item.getRecommendationRun().getId() : null)
                .note(item.getNote())
                .addedAt(item.getAddedAt())
                .isInShortlist(item.getStatus() != ShortlistStatus.REMOVED)
                .build();
    }
}
