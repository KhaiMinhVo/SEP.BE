package com.influencermatch.backend.creator;

import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.dto.CreatorIngestionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CreatorIngestionService {

    private final CreatorRepository creatorRepository;
    private final PublicCreatorMetricRepository metricRepository;

    @Transactional
    public CreatorIngestionResponse ingestCreatorData(CreatorIngestionRequest request) {
        // Find existing creator or create new one
        Creator creator = creatorRepository.findByPlatformAndExternalId(request.getPlatform(), request.getExternalId())
                .orElseGet(() -> Creator.builder()
                        .platform(request.getPlatform())
                        .externalId(request.getExternalId())
                        .build());

        // Update creator core fields
        creator.setUserName(request.getUserName());
        if (request.getLocation() != null) {
            creator.setLocation(request.getLocation());
        }
        if (request.getProfileUrl() != null) {
            creator.setProfileUrl(request.getProfileUrl());
        }

        creator = creatorRepository.save(creator);

        // Parse Freshness Status
        MetricDataStatus status = MetricDataStatus.FRESH;
        if (request.getFreshnessStatus() != null) {
            try {
                status = MetricDataStatus.valueOf(request.getFreshnessStatus().toUpperCase());
            } catch (IllegalArgumentException e) {
                // Default to FRESH if invalid
            }
        }

        // Create new metric snapshot
        PublicCreatorMetric metric = PublicCreatorMetric.builder()
                .creator(creator)
                .followers(request.getFollowers())
                .avgViews(request.getAvgViews())
                .avgLikes(request.getAvgLikes())
                .avgComments(request.getAvgComments())
                .avgShares(request.getAvgShares())
                .engagementRate(request.getEngagementRate())
                .niche(request.getNiche())
                .location(request.getLocation())
                .contentSummary(request.getContentSummary())
                .collectedAt(LocalDateTime.now())
                .freshnessStatus(status)
                .dataConfidence(request.getDataConfidence())
                .contact(request.getContact())
                .category(request.getCategory())
                .build();

        // Optional urlProfile on metric (often redundant with creator.profileUrl, but keeping for DB schema matching)
        if (request.getProfileUrl() != null) {
            metric.setUrlProfile(request.getProfileUrl());
        }

        metric = metricRepository.save(metric);

        return CreatorIngestionResponse.builder()
                .creatorId(creator.getId())
                .metricId(metric.getId())
                .status("SUCCESS")
                .message("Creator data ingested successfully")
                .build();
    }
}
