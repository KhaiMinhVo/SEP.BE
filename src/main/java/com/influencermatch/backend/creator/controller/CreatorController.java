package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.dto.CreatorVectorSearchProjection;
import com.influencermatch.backend.creator.dto.CreatorVectorSearchRequest;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;

@RestController
@RequestMapping("/creators")
@RequiredArgsConstructor
@Tag(name = "Creators", description = "Brand-facing Creator Search and Listing")
public class CreatorController {

    private final PublicCreatorMetricRepository metricRepository;
    private final CreatorRepository creatorRepository;
    private final com.influencermatch.backend.creator.client.AiServiceClient aiServiceClient;

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

    @PostMapping("/search/vector")
    @Transactional(readOnly = true)
    @Operation(summary = "Vector search creators", description = "Find creators semantically similar to the provided embedding vector.")
    public ResponseEntity<ApiResponse<List<CreatorVectorSearchProjection>>> searchByVector(
        @RequestBody CreatorVectorSearchRequest request) {
        String vectorStr = request.getVector() != null ? request.getVector().toString() : "[]";
        int limit = request.getLimit() > 0 ? request.getLimit() : 10;
        List<CreatorVectorSearchProjection> results = creatorRepository.searchSimilarCreators(vectorStr, limit);
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
            }
            return map;
        }).collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
