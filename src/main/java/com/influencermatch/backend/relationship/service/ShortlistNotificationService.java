package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.relationship.dto.ShortlistCrawlEvent;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortlistNotificationService {

  private static final Long EMITTER_TIMEOUT = 30 * 60 * 1000L; // 30 minutes
  private final Map<UUID, List<SseEmitter>> campaignEmitters = new ConcurrentHashMap<>();
  private final ShortlistItemRepository shortlistItemRepository;

  /**
   * Subscribe client to SSE event stream for a specific campaign.
   */
  public SseEmitter subscribe(UUID campaignId) {
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);

    campaignEmitters.computeIfAbsent(campaignId, k -> new CopyOnWriteArrayList<>()).add(emitter);

    emitter.onCompletion(() -> removeEmitter(campaignId, emitter));
    emitter.onTimeout(() -> removeEmitter(campaignId, emitter));
    emitter.onError(e -> removeEmitter(campaignId, emitter));

    try {
      ShortlistCrawlEvent initEvent =
          ShortlistCrawlEvent.builder()
              .eventType("CONNECTED")
              .campaignId(campaignId)
              .message("Kết nối thành công tới kênh cập nhật Shortlist theo thời gian thực")
              .timestamp(LocalDateTime.now())
              .build();

      emitter.send(
          SseEmitter.event()
              .name("SHORTLIST_EVENT")
              .data(initEvent, MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      log.warn("Failed to send initial SSE connect event for campaign {}: {}", campaignId, e.getMessage());
      removeEmitter(campaignId, emitter);
    }

    return emitter;
  }

  /**
   * Notify frontend that a crawl job has been initiated for a creator.
   */
  public void notifyCrawlStarted(
      UUID campaignId, UUID creatorId, String creatorName, String platform, String userName) {
    ShortlistCrawlEvent event =
        ShortlistCrawlEvent.builder()
            .eventType("CRAWL_STARTED")
            .campaignId(campaignId)
            .creatorId(creatorId)
            .creatorName(creatorName)
            .platform(platform)
            .userName(userName)
            .message("Đang làm mới dữ liệu cho @" + (userName != null ? userName : creatorName) + "...")
            .timestamp(LocalDateTime.now())
            .build();

    broadcast(campaignId, event);
  }

  /**
   * Broadcast completed crawl metrics to all campaigns containing this creator.
   */
  public void notifyCrawlCompleted(
      UUID creatorId,
      String platform,
      String userName,
      String displayName,
      String avatarUrl,
      Long followers,
      BigDecimal avgViews,
      BigDecimal avgLikes,
      BigDecimal avgComments,
      BigDecimal avgShares,
      BigDecimal engagementRate) {

    List<ShortlistItem> activeItems;
    try {
      activeItems =
          shortlistItemRepository.findByCreatorIdAndStatusNot(creatorId, ShortlistStatus.REMOVED);
    } catch (Exception e) {
      log.warn("Could not query active shortlist items for creator {}: {}", creatorId, e.getMessage());
      return;
    }

    if (activeItems == null || activeItems.isEmpty()) {
      return;
    }

    LocalDateTime now = LocalDateTime.now();
    for (ShortlistItem item : activeItems) {
      if (item.getCampaign() == null || item.getCampaign().getId() == null) {
        continue;
      }
      UUID campaignId = item.getCampaign().getId();

      ShortlistCrawlEvent event =
          ShortlistCrawlEvent.builder()
              .eventType("CRAWL_COMPLETED")
              .campaignId(campaignId)
              .creatorId(creatorId)
              .creatorName(displayName != null ? displayName : userName)
              .platform(platform)
              .userName(userName)
              .avatarUrl(avatarUrl)
              .followers(followers)
              .avgViews(avgViews)
              .avgLikes(avgLikes)
              .avgComments(avgComments)
              .avgShares(avgShares)
              .engagementRate(engagementRate)
              .message("Đã cập nhật số liệu mới nhất cho @" + (userName != null ? userName : displayName))
              .timestamp(now)
              .build();

      broadcast(campaignId, event);
    }
  }

  /**
   * Send event to all active emitters for a campaign.
   */
  public void broadcast(UUID campaignId, ShortlistCrawlEvent event) {
    List<SseEmitter> emitters = campaignEmitters.get(campaignId);
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(
            SseEmitter.event()
                .name("SHORTLIST_EVENT")
                .data(event, MediaType.APPLICATION_JSON));
      } catch (Exception e) {
        log.debug("Removing disconnected emitter for campaign {}: {}", campaignId, e.getMessage());
        removeEmitter(campaignId, emitter);
      }
    }
  }

  private void removeEmitter(UUID campaignId, SseEmitter emitter) {
    List<SseEmitter> emitters = campaignEmitters.get(campaignId);
    if (emitters != null) {
      emitters.remove(emitter);
      if (emitters.isEmpty()) {
        campaignEmitters.remove(campaignId);
      }
    }
  }
}
