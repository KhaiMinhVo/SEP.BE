package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.dto.CreatorVectorSearchProjection;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.creator.dto.CreatorDetailResponse;
import com.influencermatch.backend.creator.service.CreatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/creators")
@RequiredArgsConstructor
@Tag(name = "Creators", description = "Brand-facing Creator Search, Listing, and Data Ingestion")
public class CreatorController {

    private final PublicCreatorMetricRepository metricRepository;
    private final CreatorRepository creatorRepository;
    private final AiServiceClient aiServiceClient;
    private final CreatorService creatorService;

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_CREATOR_PROFILE')")
    @Transactional(readOnly = true)
    @Operation(summary = "Get creator profile details", description = "Get full creator profile including metrics, rate card, and recent posts.")
    public ResponseEntity<ApiResponse<CreatorDetailResponse>> getCreatorDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(creatorService.getCreatorDetail(id)));
    }

    @GetMapping("/search/semantic")
    @Transactional(readOnly = true)
    @Operation(
        summary = "Semantic Search creators by natural language query",
        description = "Calls Python AI Service (BKAI model) to convert text into a 768-dim vector, then queries PostgreSQL pgvector.")
    public ResponseEntity<ApiResponse<List<CreatorVectorSearchProjection>>> searchSemantic(
        @RequestParam String q,
        @RequestParam(defaultValue = "10") int limit) {
        if (q == null || q.isBlank()) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "Query string 'q' cannot be empty");
        }
        List<Float> vector = aiServiceClient.getEmbedding(q.trim());
        if (vector.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI Service is unreachable or failed to generate vector");
        }
        int effectiveLimit = Math.max(1, Math.min(limit, 50));
        List<CreatorVectorSearchProjection> results =
            creatorRepository.searchSimilarCreators(vector.toString(), effectiveLimit);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "List all creators", description = "Get a list of all ingested creators in the system.")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllCreators() {
        List<PublicCreatorMetric> metrics = metricRepository.findAll();
        
        List<Map<String, Object>> result = metrics.stream().map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("followers", m.getFollowers());
            map.put("avgViews", m.getAvgViews());
            map.put("avgLikes", m.getAvgLikes());
            map.put("avgComments", m.getAvgComments());
            map.put("avgShares", m.getAvgShares());
            map.put("niche", m.getNiche());
            map.put("category", m.getCategory());
            map.put("engagementRate", m.getEngagementRate());
            map.put("contentSummary", m.getContentSummary());
            
            if (m.getCreator() != null) {
                map.put("creatorId", m.getCreator().getId());
                map.put("userName", m.getCreator().getUserName());
                map.put("displayName", m.getCreator().getDisplayName());
                map.put("platform", m.getCreator().getPlatform());
                map.put("avatarUrl", m.getCreator().getAvatarUrl());
                map.put("profileUrl", m.getCreator().getProfileUrl());
                map.put("sourceType", m.getCreator().getSourceType());
                map.put("verifiedByInfluencerMatch", m.getCreator().getSourceType()==com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED && m.getCreator().getOwner()!=null);
                map.put("authorizedAt", m.getCreator().getAuthorizedAt());
            }
            return map;
        }).collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
