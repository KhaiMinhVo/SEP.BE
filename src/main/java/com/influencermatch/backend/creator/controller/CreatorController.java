package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/creators")
@RequiredArgsConstructor
@Tag(name = "Creators", description = "Brand-facing Creator Search and Listing")
public class CreatorController {

    private final PublicCreatorMetricRepository metricRepository;

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
