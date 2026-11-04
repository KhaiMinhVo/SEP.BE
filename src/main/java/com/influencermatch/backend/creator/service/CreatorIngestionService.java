package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.dto.CreatorIngestionResponse;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.relationship.service.ShortlistNotificationService;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class CreatorIngestionService {

  private final CreatorRepository creatorRepository;
  private final PublicCreatorMetricRepository metricRepository;
  private final PostRepository postRepository;
  private final jakarta.persistence.EntityManager entityManager;
  private final ShortlistNotificationService shortlistNotificationService;

  @Autowired
  public CreatorIngestionService(
      CreatorRepository creatorRepository,
      PublicCreatorMetricRepository metricRepository,
      PostRepository postRepository,
      jakarta.persistence.EntityManager entityManager,
      @Autowired(required = false) ShortlistNotificationService shortlistNotificationService) {
    this.creatorRepository = creatorRepository;
    this.metricRepository = metricRepository;
    this.postRepository = postRepository;
    this.entityManager = entityManager;
    this.shortlistNotificationService = shortlistNotificationService;
  }

  public CreatorIngestionService(
      CreatorRepository creatorRepository,
      PublicCreatorMetricRepository metricRepository,
      PostRepository postRepository,
      jakarta.persistence.EntityManager entityManager) {
    this(creatorRepository, metricRepository, postRepository, entityManager, null);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('SERVICE_INGEST_CREATOR')")
  public CreatorIngestionResponse ingestCreatorData(CreatorIngestionRequest request) {
    // 0. Data Quality Gatekeeper: Chỉ tiếp nhận KOL hoặc KOC
    String type = request.getCreatorType();
    if (type == null || (!"KOL".equalsIgnoreCase(type) && !"KOC".equalsIgnoreCase(type))) {
      return CreatorIngestionResponse.builder()
          .status("REJECTED")
          .message("Rejected: Only KOL or KOC accounts are accepted for market discovery.")
          .build();
    }

    // Kiểm tra ngưỡng followers tối thiểu (5,000 followers)
    if (request.getFollowers() != null && request.getFollowers() < 5000) {
      return CreatorIngestionResponse.builder()
          .status("REJECTED")
          .message("Rejected: Minimum 5,000 followers required for market discovery.")
          .build();
    }

    // 1. Advisory Lock to prevent concurrent ingestion of the exact same account
    creatorRepository.acquireIdentityLock(request.getPlatform() + ":" + request.getExternalId());

    // 2. Find existing creator or create new one
    Creator creator =
        creatorRepository
            .findLockedByPlatformAndExternalId(request.getPlatform(), request.getExternalId())
            .orElseGet(
                () ->
                    Creator.builder()
                        .master(null)
                        .sourceType(CreatorSourceType.PUBLIC)
                        .platform(request.getPlatform())
                        .externalId(request.getExternalId())
                        .isStableId(request.getIsStableExternalId() != null ? request.getIsStableExternalId() : true)
                        .build());

    if (creator.getId() != null) {
      entityManager.refresh(creator);
      if (request.getIsStableExternalId() != null) {
        creator.setIsStableId(request.getIsStableExternalId());
      }
    }

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

    // 3. Snapshot Idempotency check: if crawlRunId already recorded for this creator, skip duplicate metric snapshot
    if (request.getCrawlRunId() != null
        && savedCreator.getId() != null
        && metricRepository.existsByCreatorIdAndCrawlRunId(savedCreator.getId(), request.getCrawlRunId())) {
      PublicCreatorMetric existingMetric = metricRepository.findByCreatorId(savedCreator.getId()).orElse(null);
      return CreatorIngestionResponse.builder()
          .creatorId(savedCreator.getId())
          .metricId(existingMetric != null ? existingMetric.getId() : null)
          .status("SKIPPED")
          .message("Snapshot already recorded for crawlRunId: " + request.getCrawlRunId())
          .build();
    }

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
    metric.setContentSummary(request.getContentSummary());
    metric.setCollectedAt(LocalDateTime.now());
    metric.setCrawlRunId(request.getCrawlRunId());
    metric.setObservedAt(request.getObservedAt() != null ? request.getObservedAt() : LocalDateTime.now());
    metric.setCreatorType(request.getCreatorType());
    metric.setFreshnessStatus(status);
    metric.setDataConfidence(request.getDataConfidence());
    metric.setContact(request.getContact());
    metric.setCategory(request.getCategory());

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

    if (shortlistNotificationService != null) {
      try {
        shortlistNotificationService.notifyCrawlCompleted(
            savedCreator.getId(),
            savedCreator.getPlatform(),
            savedCreator.getUserName(),
            savedCreator.getDisplayName(),
            savedCreator.getAvatarUrl(),
            request.getFollowers(),
            request.getAvgViews(),
            request.getAvgLikes(),
            request.getAvgComments(),
            request.getAvgShares(),
            request.getEngagementRate());
      } catch (Exception e) {
        log.warn("Failed to notify shortlist crawl completion for creator {}: {}", savedCreator.getId(), e.getMessage());
      }
    }

    return CreatorIngestionResponse.builder()
        .creatorId(savedCreator.getId())
        .metricId(metric.getId())
        .status("SUCCESS")
        .message("Creator data ingested successfully")
        .build();
  }
}
