package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.relationship.dto.CreateRelationshipRequest;
import com.influencermatch.backend.relationship.dto.RelationshipDto;
import com.influencermatch.backend.relationship.dto.UpdateRelationshipDetailsRequest;
import com.influencermatch.backend.relationship.dto.UpdateRelationshipStageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface RelationshipService {
    RelationshipDto createRelationship(UUID campaignId, CreateRelationshipRequest request);
    RelationshipDto updateStage(UUID campaignId, UUID relationshipId, UpdateRelationshipStageRequest request);
    RelationshipDto updateDetails(UUID campaignId, UUID relationshipId, UpdateRelationshipDetailsRequest request);
    Page<RelationshipDto> getRelationshipsByCampaign(UUID campaignId, Pageable pageable);
    RelationshipDto getRelationship(UUID campaignId, UUID relationshipId);
}
