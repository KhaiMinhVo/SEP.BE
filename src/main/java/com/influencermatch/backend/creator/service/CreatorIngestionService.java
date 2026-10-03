package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.controller.*;
import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.dto.CreatorIngestionResponse;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreatorIngestionService {

  private final CreatorRepository creatorRepository;
  private final PublicCreatorMetricRepository metricRepository;
  private final PostRepository postRepository;

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('SERVICE_INGEST_CREATOR')")
  public CreatorIngestionResponse ingestCreatorData(CreatorIngestionRequest request) {
    // Find existing creator or create new one
    Creator creator =
        creatorRepository
            .findByPlatformAndExternalId(request.getPlatform(), request.getExternalId())
            .orElseGet(
                () ->
                    Creator.builder()
                        .platform(request.getPlatform())
                        .externalId(request.getExternalId())
                        .build());

    // Update creator core fields
    creator.setUserName(request.getUserName());
    if (request.getDisplayName() != null) {
      creator.setDisplayName(request.getDisplayName());
    }
    if (request.getAvatarUrl() != null) {
      creator.setAvatarUrl(request.getAvatarUrl());
    }
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
    PublicCreatorMetric metric =
        PublicCreatorMetric.builder()
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
            .creatorType(request.getCreatorType())
            .freshnessStatus(status)
            .dataConfidence(request.getDataConfidence())
            .contact(request.getContact())
            .category(request.getCategory())
            .build();

    // Optional urlProfile on metric (often redundant with creator.profileUrl, but keeping for DB
    // schema matching)
    if (request.getProfileUrl() != null) {
      metric.setUrlProfile(request.getProfileUrl());
    }

    metric = metricRepository.save(metric);

    if (request.getRecentPosts() != null && !request.getRecentPosts().isEmpty()) {
      postRepository.deleteByCreatorId(creator.getId());
      for (PostDto dto : request.getRecentPosts()) {
        Post post =
            Post.builder()
                .creator(creator)
                .platformPostId(dto.getPlatformPostId())
                .postUrl(dto.getPostUrl())
                .caption(dto.getCaption())
                .views(dto.getViews())
                .likes(dto.getLikes())
                .comments(dto.getComments())
                .shares(dto.getShares())
                .postedAt(dto.getPostedAt())
                .build();
        postRepository.save(post);
      }
    }

    return CreatorIngestionResponse.builder()
        .creatorId(creator.getId())
        .metricId(metric.getId())
        .status("SUCCESS")
        .message("Creator data ingested successfully")
        .build();
  }
}
