package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.relationship.dto.ShortlistItemDto;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import java.util.*;
import java.util.stream.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ShortlistProjectionService {
  private final PublicCreatorMetricRepository metrics;

  public Map<UUID, PublicCreatorMetric> latest(Collection<ShortlistItem> items) {
    var ids = items.stream().map(i -> i.getCreator().getId()).distinct().toList();
    return ids.isEmpty()
        ? Map.of()
        : metrics.latestForCreators(ids).stream()
            .collect(Collectors.toMap(m -> m.getCreator().getId(), m -> m));
  }

  public ShortlistItemDto one(ShortlistItem i) {
    return map(i, latest(List.of(i)).get(i.getCreator().getId()));
  }

  public List<ShortlistItemDto> list(List<ShortlistItem> items) {
    var snapshots = latest(items);
    return items.stream().map(i -> map(i, snapshots.get(i.getCreator().getId()))).toList();
  }

  public ShortlistItemDto map(ShortlistItem i, PublicCreatorMetric m) {
    var c = i.getCreator();
    return ShortlistItemDto.builder()
        .id(i.getId())
        .campaignId(i.getCampaign().getId())
        .creatorId(c.getId())
        .masterId(c.getMaster() == null ? null : c.getMaster().getId())
        .creatorName(c.getDisplayName() != null ? c.getDisplayName() : c.getUserName())
        .platform(c.getPlatform())
        .avatarUrl(c.getAvatarUrl())
        .metricId(m == null ? null : m.getId())
        .collectedAt(m == null ? null : m.getCollectedAt())
        .dataConfidence(m == null ? null : m.getDataConfidence())
        .freshnessStatus(m == null ? null : m.getFreshnessStatus())
        .followers(m == null ? null : m.getFollowers())
        .engagementRate(m == null ? null : m.getEngagementRate())
        .niche(m == null ? null : m.getNiche())
        .priority(i.getPriority())
        .status(i.getStatus())
        .source(i.getSource())
        .recommendationRunId(
            i.getRecommendationRun() == null ? null : i.getRecommendationRun().getId())
        .note(i.getNote())
        .addedAt(i.getAddedAt())
        .isInShortlist(i.getStatus() != ShortlistStatus.REMOVED)
        .refreshRequestStatus("NOT_REQUESTED")
        .build();
  }
}
