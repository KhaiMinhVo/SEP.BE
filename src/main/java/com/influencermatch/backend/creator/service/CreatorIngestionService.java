package com.influencermatch.backend.creator.service;

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
  private final jakarta.persistence.EntityManager entityManager;

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('SERVICE_INGEST_CREATOR')")
  public CreatorIngestionResponse ingestCreatorData(CreatorIngestionRequest request) {
    // Find existing creator or create new one
    Creator creator =
        creatorRepository
            .findLockedByPlatformAndExternalId(request.getPlatform(), request.getExternalId())
            .orElseGet(
                () ->
                    Creator.builder()
                        .platform(request.getPlatform())
                        .externalId(request.getExternalId())
                        .build());

    if (creator.getId() != null) entityManager.refresh(creator);
    // Only public core fields are writable by ingestion; ownership and rates are not accepted.
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

    final Creator savedCreator = creatorRepository.save(creator);

    // Parse Freshness Status
    MetricDataStatus status = MetricDataStatus.FRESH;
    if (request.getFreshnessStatus() != null) {
      try {
        status = MetricDataStatus.valueOf(request.getFreshnessStatus().toUpperCase());
      } catch (IllegalArgumentException e) {
        // Default to FRESH if invalid
      }
    }

    // Upsert metric: update existing metric if found, otherwise create new one
    PublicCreatorMetric metric =
        metricRepository.findByCreatorId(savedCreator.getId()).orElseGet(
            () -> PublicCreatorMetric.builder().creator(savedCreator).build());

    metric.setFollowers(request.getFollowers());
    metric.setAvgViews(request.getAvgViews());
    metric.setAvgLikes(request.getAvgLikes());
    metric.setAvgComments(request.getAvgComments());
    metric.setAvgShares(request.getAvgShares());
    metric.setEngagementRate(request.getEngagementRate());
    metric.setNiche(request.getNiche());
    metric.setLocation(request.getLocation());
    metric.setContentSummary(request.getContentSummary());
    metric.setCollectedAt(LocalDateTime.now());
    metric.setCreatorType(request.getCreatorType());
    metric.setFreshnessStatus(status);
    metric.setDataConfidence(request.getDataConfidence());
    metric.setContact(request.getContact());
    metric.setCategory(request.getCategory());

    // Optional urlProfile on metric (often redundant with creator.profileUrl, but keeping for DB
    // schema matching)
    if (request.getProfileUrl() != null) {
      metric.setUrlProfile(request.getProfileUrl());
    }

    metric = metricRepository.save(metric);

    if (request.getRecentPosts() != null && !request.getRecentPosts().isEmpty()) {
      postRepository.deleteByCreatorId(savedCreator.getId());
      for (PostDto dto : request.getRecentPosts()) {
        Post post =
            Post.builder()
                .creator(savedCreator)
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

    if (request.getEmbedding() != null && !request.getEmbedding().isEmpty()) {
      creatorRepository.updateEmbedding(savedCreator.getId(), request.getEmbedding().toString());
    }

    return CreatorIngestionResponse.builder()
        .creatorId(savedCreator.getId())
        .metricId(metric.getId())
        .status("SUCCESS")
        .message("Creator data ingested successfully")
        .build();
  }
}
