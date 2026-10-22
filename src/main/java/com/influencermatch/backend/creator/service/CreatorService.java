package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.dto.CreatorDetailResponse;
import com.influencermatch.backend.creator.dto.PostDto;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PostRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.exception.NotFoundException;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreatorService {

  private final CreatorRepository creatorRepository;
  private final PublicCreatorMetricRepository metricRepository;
  private final PostRepository postRepository;

  @Transactional(readOnly = true)
  @PreAuthorize("hasAuthority('VIEW_CREATOR_PROFILE')")
  public CreatorDetailResponse getCreatorDetail(UUID id) {
    Permissions.require(Permission.VIEW_CREATOR_PROFILE);
    Creator c = creatorRepository.findById(id)
        .orElseThrow(() -> new NotFoundException("Creator not found"));

    boolean verified = c.getSourceType() == CreatorSourceType.AUTHORIZED && c.getOwner() != null;
    PublicCreatorMetric metric = metricRepository.latestForCreators(List.of(id)).stream().findFirst().orElse(null);

    List<PostDto> recentPosts = postRepository.findByCreatorId(c.getId()).stream()
        .map(p -> PostDto.builder()
            .platformPostId(p.getPlatformPostId())
            .postUrl(p.getPostUrl())
            .caption(p.getCaption())
            .views(p.getViews())
            .likes(p.getLikes())
            .comments(p.getComments())
            .shares(p.getShares())
            .postedAt(p.getPostedAt() != null ? p.getPostedAt().toString() : null)
            .build())
        .toList();

    return CreatorDetailResponse.builder()
        .id(c.getId())
        .platform(c.getPlatform())
        .externalId(c.getExternalId())
        .userName(c.getUserName())
        .displayName(c.getDisplayName())
        .profileUrl(c.getProfileUrl())
        .avatarUrl(c.getAvatarUrl())
        .location(c.getLocation())
        .sourceType(c.getSourceType())
        .verifiedByInfluencerMatch(verified)
        .authorizedAt(c.getAuthorizedAt())
        .followers(metric != null ? metric.getFollowers() : null)
        .avgViews(metric != null ? metric.getAvgViews() : null)
        .avgLikes(metric != null ? metric.getAvgLikes() : null)
        .avgComments(metric != null ? metric.getAvgComments() : null)
        .avgShares(metric != null ? metric.getAvgShares() : null)
        .engagementRate(metric != null ? metric.getEngagementRate() : null)
        .niche(metric != null ? metric.getNiche() : null)
        .category(metric != null ? metric.getCategory() : null)
        .contentSummary(metric != null ? metric.getContentSummary() : null)
        .recentPosts(recentPosts)
        .build();
  }
}
