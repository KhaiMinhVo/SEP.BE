package com.influencermatch.backend.discovery.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.discovery.dto.DiscoverySearchRequest;
import com.influencermatch.backend.discovery.dto.HybridDiscoveryCreatorProjection;
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
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('SEARCH_CREATOR')")
    @Transactional(readOnly = true)
    @Operation(summary = "Discover creators for campaign", description = "Executes the 4-Stage Hybrid Discovery Pipeline (Hard Filters + Semantic & FTS Search + Rank Fusion + Fallback) to find creators.")
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
            
        Page<HybridDiscoveryCreatorProjection> pool = discoveryService.discoverCreators(campaignId, searchRequest);
        
        Page<Map<String, Object>> result = pool.map(p -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", p.getId());
            map.put("creatorId", p.getCreatorId());
            map.put("userName", p.getUserName());
            map.put("displayName", p.getDisplayName());
            map.put("platform", p.getPlatform());
            map.put("avatarUrl", p.getAvatarUrl());
            map.put("profileUrl", p.getProfileUrl());
            map.put("followers", p.getFollowers());
            map.put("niche", p.getNiche());
            map.put("category", p.getCategory());
            map.put("engagementRate", p.getEngagementRate());
            map.put("contentSummary", p.getContentSummary());
            
            // Điểm số xếp hạng Hybrid
            map.put("semanticScore", p.getSemanticScore());
            map.put("ftsScore", p.getFtsScore());
            map.put("hybridScore", p.getHybridScore());
            return map;
        });
        
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
