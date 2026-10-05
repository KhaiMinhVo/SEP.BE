package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.relationship.dto.CreateRelationshipRequest;
import com.influencermatch.backend.relationship.dto.RelationshipDto;
import com.influencermatch.backend.relationship.dto.UpdateRelationshipDetailsRequest;
import com.influencermatch.backend.relationship.dto.UpdateRelationshipStageRequest;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RelationshipServiceImpl implements RelationshipService {

    private final BrandCreatorRelationshipRepository relationshipRepository;
    private final ShortlistItemRepository shortlistItemRepository;
    private final CampaignRepository campaignRepository;
    private final CreatorRepository creatorRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public RelationshipDto createRelationship(UUID campaignId, CreateRelationshipRequest request, UUID userId) {
        if (relationshipRepository.existsByCampaignIdAndCreatorId(campaignId, request.getCreatorId())) {
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "Relationship already exists for this creator in the campaign");
        }

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Campaign not found"));

        Creator creator = creatorRepository.findById(request.getCreatorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Creator not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));

        ShortlistItem shortlistItem = null;
        if (request.getShortlistItemId() != null) {
            shortlistItem = shortlistItemRepository.findById(request.getShortlistItemId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Shortlist item not found"));
        }

        BrandCreatorRelationship relationship = BrandCreatorRelationship.builder()
                .campaign(campaign)
                .creator(creator)
                .user(user)
                .shortlistItem(shortlistItem)
                .stage(RelationshipStage.CONTACTED) // Default starting stage
                .contactMethod(request.getContactMethod())
                .quotedFee(request.getQuotedFee())
                .currency(request.getCurrency())
                .notes(request.getNotes())
                .lastContactAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        relationship = relationshipRepository.save(relationship);
        return mapToDto(relationship);
    }

    @Override
    @Transactional
    public RelationshipDto updateStage(UUID relationshipId, UpdateRelationshipStageRequest request, UUID userId) {
        BrandCreatorRelationship relationship = getEntity(relationshipId, userId);
        relationship.setStage(request.getStage());
        relationship.setUpdatedAt(LocalDateTime.now());
        return mapToDto(relationshipRepository.save(relationship));
    }

    @Override
    @Transactional
    public RelationshipDto updateDetails(UUID relationshipId, UpdateRelationshipDetailsRequest request, UUID userId) {
        BrandCreatorRelationship relationship = getEntity(relationshipId, userId);
        
        if (request.getContactMethod() != null) relationship.setContactMethod(request.getContactMethod());
        if (request.getQuotedFee() != null) relationship.setQuotedFee(request.getQuotedFee());
        if (request.getAgreedFee() != null) relationship.setAgreedFee(request.getAgreedFee());
        if (request.getCurrency() != null) relationship.setCurrency(request.getCurrency());
        if (request.getNotes() != null) relationship.setNotes(request.getNotes());
        if (request.getNextAction() != null) relationship.setNextAction(request.getNextAction());
        
        relationship.setUpdatedAt(LocalDateTime.now());
        return mapToDto(relationshipRepository.save(relationship));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RelationshipDto> getRelationshipsByCampaign(UUID campaignId, Pageable pageable) {
        return relationshipRepository.findByCampaignId(campaignId, pageable).map(this::mapToDto);
    }

    @Override
    @Transactional(readOnly = true)
    public RelationshipDto getRelationship(UUID relationshipId, UUID userId) {
        return mapToDto(getEntity(relationshipId, userId));
    }

    private BrandCreatorRelationship getEntity(UUID id, UUID userId) {
        return relationshipRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Relationship not found or access denied"));
    }

    private RelationshipDto mapToDto(BrandCreatorRelationship entity) {
        return RelationshipDto.builder()
                .id(entity.getId())
                .campaignId(entity.getCampaign().getId())
                .creatorId(entity.getCreator().getId())
                .creatorName(entity.getCreator().getDisplayName())
                .shortlistItemId(entity.getShortlistItem() != null ? entity.getShortlistItem().getId() : null)
                .stage(entity.getStage())
                .contactMethod(entity.getContactMethod())
                .quotedFee(entity.getQuotedFee())
                .agreedFee(entity.getAgreedFee())
                .currency(entity.getCurrency())
                .lastContactAt(entity.getLastContactAt())
                .notes(entity.getNotes())
                .nextAction(entity.getNextAction())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
