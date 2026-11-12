package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.relationship.dto.ShortlistCrawlEvent;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import java.io.IOException;
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
  private final CampaignOwnershipService ownership;

  /** Subscribe client to SSE event stream for a specific campaign. */
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public SseEmitter subscribe(UUID campaignId) {
    com.influencermatch.backend.security.permission.Permissions.require(
        com.influencermatch.backend.security.permission.Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);
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
          SseEmitter.event().name("SHORTLIST_EVENT").data(initEvent, MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      log.warn(
          "Failed to send initial SSE connect event for campaign {}: {}",
          campaignId,
          e.getMessage());
      removeEmitter(campaignId, emitter);
    }

    return emitter;
  }

  /** Called after ingestion commits; event contains only persisted public metric fields. */
  @org.springframework.transaction.annotation.Transactional(
      readOnly = true,
      propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void notifyCommittedCrawl(ShortlistCrawlEvent event) {
    var items =
        shortlistItemRepository.findByCreatorIdAndStatusNot(
            event.getCreatorId(), ShortlistStatus.REMOVED);
    if (items == null) return;
    for (var item : items) {
      if (item.getCampaign() == null) continue;
      var copy = new ShortlistCrawlEvent();
      org.springframework.beans.BeanUtils.copyProperties(event, copy);
      copy.setCampaignId(item.getCampaign().getId());
      broadcast(copy.getCampaignId(), copy);
    }
  }

  /** Send event to all active emitters for a campaign. */
  public void broadcast(UUID campaignId, ShortlistCrawlEvent event) {
    List<SseEmitter> emitters = campaignEmitters.get(campaignId);
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(
            SseEmitter.event().name("SHORTLIST_EVENT").data(event, MediaType.APPLICATION_JSON));
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
