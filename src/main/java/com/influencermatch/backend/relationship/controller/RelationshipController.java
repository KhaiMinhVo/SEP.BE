package com.influencermatch.backend.relationship.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.relationship.dto.CreateRelationshipRequest;
import com.influencermatch.backend.relationship.dto.RelationshipDto;
import com.influencermatch.backend.relationship.dto.UpdateRelationshipDetailsRequest;
import com.influencermatch.backend.relationship.dto.UpdateRelationshipStageRequest;
import com.influencermatch.backend.relationship.service.RelationshipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/campaigns/{campaignId}/relationships")
@RequiredArgsConstructor
@Tag(name = "Relationship Management")
public class RelationshipController {

    private final RelationshipService relationshipService;

    @Operation(summary = "Start a relationship with a creator", security = @SecurityRequirement(name = "BearerAuth"))
    @PostMapping
    @PreAuthorize("hasAuthority('UPDATE_CAMPAIGN')")
    public ResponseEntity<ApiResponse<RelationshipDto>> createRelationship(
            @PathVariable UUID campaignId,
            @Valid @RequestBody CreateRelationshipRequest request) {
        RelationshipDto dto = relationshipService.createRelationship(campaignId, request);
        return ResponseEntity.ok(ApiResponse.ok("Relationship initiated.", dto));
    }

    @Operation(summary = "Get relationships for a campaign", security = @SecurityRequirement(name = "BearerAuth"))
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_OWN_CAMPAIGN')")
    public ResponseEntity<ApiResponse<Page<RelationshipDto>>> getRelationships(
            @PathVariable UUID campaignId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<RelationshipDto> result = relationshipService.getRelationshipsByCampaign(campaignId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @Operation(summary = "Get relationship details", security = @SecurityRequirement(name = "BearerAuth"))
    @GetMapping("/{relationshipId}")
    @PreAuthorize("hasAuthority('VIEW_OWN_CAMPAIGN')")
    public ResponseEntity<ApiResponse<RelationshipDto>> getRelationship(
            @PathVariable UUID campaignId,
            @PathVariable UUID relationshipId) {
        RelationshipDto dto = relationshipService.getRelationship(campaignId, relationshipId);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @Operation(summary = "Update relationship stage (e.g., from CONTACTED to NEGOTIATING)", security = @SecurityRequirement(name = "BearerAuth"))
    @PatchMapping("/{relationshipId}/stage")
    @PreAuthorize("hasAuthority('UPDATE_CAMPAIGN')")
    public ResponseEntity<ApiResponse<RelationshipDto>> updateStage(
            @PathVariable UUID campaignId,
            @PathVariable UUID relationshipId,
            @Valid @RequestBody UpdateRelationshipStageRequest request) {
        RelationshipDto dto = relationshipService.updateStage(campaignId, relationshipId, request);
        return ResponseEntity.ok(ApiResponse.ok("Stage updated.", dto));
    }

    @Operation(summary = "Update negotiation details (fees, notes, next action)", security = @SecurityRequirement(name = "BearerAuth"))
    @PatchMapping("/{relationshipId}/details")
    @PreAuthorize("hasAuthority('UPDATE_CAMPAIGN')")
    public ResponseEntity<ApiResponse<RelationshipDto>> updateDetails(
            @PathVariable UUID campaignId,
            @PathVariable UUID relationshipId,
            @Valid @RequestBody UpdateRelationshipDetailsRequest request) {
        RelationshipDto dto = relationshipService.updateDetails(campaignId, relationshipId, request);
        return ResponseEntity.ok(ApiResponse.ok("Details updated.", dto));
    }
}
