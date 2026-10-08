package com.influencermatch.backend.discovery.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.discovery.dto.DiscoverySearchRequest;
import com.influencermatch.backend.discovery.service.CreatorDiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/campaigns")
@RequiredArgsConstructor
@Tag(name = "Creator Discovery", description = "Discovery pipeline for finding creators for a specific campaign")
public class CreatorDiscoveryController {

    private final CreatorDiscoveryService discoveryService;

    @GetMapping("/{campaignId}/discovery")
    @Transactional(readOnly = true)
    @Operation(summary = "Discover creators for campaign", description = "Executes the Hybrid Discovery Pipeline to find creators matching campaign context and UI search parameters.")
    public ResponseEntity<ApiResponse<Page<Map<String, Object>>>> discoverCreators(
            @PathVariable UUID campaignId,
            @ParameterObject @ModelAttribute DiscoverySearchRequest searchRequest) {
            
        // Validate page size to prevent Spring Data JPA IllegalArgumentException
        if (searchRequest.getSize() == null || searchRequest.getSize() < 1) {
            searchRequest.setSize(20);
        }
        if (searchRequest.getPage() == null || searchRequest.getPage() < 0) {
            searchRequest.setPage(0);
        }
            
        Page<PublicCreatorMetric> pool = discoveryService.discoverCreators(campaignId, searchRequest);
        
        Page<Map<String, Object>> result = pool.map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("followers", m.getFollowers());
            map.put("niche", m.getNiche());
            map.put("engagementRate", m.getEngagementRate());
            map.put("contentSummary", m.getContentSummary());
            
            if (m.getCreator() != null) {
                map.put("creatorId", m.getCreator().getId());
                map.put("userName", m.getCreator().getUserName());
                map.put("displayName", m.getCreator().getDisplayName());
                map.put("platform", m.getCreator().getPlatform());
                map.put("avatarUrl", m.getCreator().getAvatarUrl());
                map.put("profileUrl", m.getCreator().getProfileUrl());
            }
            return map;
        });
        
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}