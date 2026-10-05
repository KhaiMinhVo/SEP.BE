package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.relationship.dto.CreateRelationshipRequest;
import com.influencermatch.backend.relationship.dto.RelationshipDto;
import com.influencermatch.backend.relationship.dto.UpdateRelationshipDetailsRequest;
import com.influencermatch.backend.relationship.dto.UpdateRelationshipStageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface RelationshipService {
    RelationshipDto createRelationship(UUID campaignId, CreateRelationshipRequest request, UUID userId);
    RelationshipDto updateStage(UUID relationshipId, UpdateRelationshipStageRequest request, UUID userId);
    RelationshipDto updateDetails(UUID relationshipId, UpdateRelationshipDetailsRequest request, UUID userId);
    Page<RelationshipDto> getRelationshipsByCampaign(UUID campaignId, Pageable pageable);
    RelationshipDto getRelationship(UUID relationshipId, UUID userId);
}
