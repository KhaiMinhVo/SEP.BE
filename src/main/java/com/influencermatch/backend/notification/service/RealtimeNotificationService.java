package com.influencermatch.backend.notification.service;

import com.influencermatch.backend.notification.dto.NotificationDto;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Luồng 15.1: Manages active SSE connections per user, lifecycle hooks, and periodic keepalive.
 */
@Slf4j
@Service
public class RealtimeNotificationService {

  private static final Long EMITTER_TIMEOUT = 30 * 60 * 1000L; // 30 minutes
  private final Map<UUID, List<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

  /**
   * Subscribe user to their personal realtime SSE notification channel.
   */
  public SseEmitter subscribe(UUID userId) {
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);

    userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

    emitter.onCompletion(() -> removeEmitter(userId, emitter));
    emitter.onTimeout(() -> removeEmitter(userId, emitter));
    emitter.onError(e -> removeEmitter(userId, emitter));

    try {
      emitter.send(
          SseEmitter.event()
              .name("CONNECTED")
              .data(
                  Map.of(
                      "status", "CONNECTED",
                      "userId", userId.toString(),
                      "message", "Realtime notification stream established"),
                  MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      log.warn("Failed to send initial SSE connect event to user {}: {}", userId, e.getMessage());
      removeEmitter(userId, emitter);
    }

    return emitter;
  }

  /**
   * Dispatch notification to all active SSE streams connected for this user.
   */
  public void sendNotification(UUID userId, NotificationDto notification) {
    List<SseEmitter> emitters = userEmitters.get(userId);
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(
            SseEmitter.event()
                .name("NOTIFICATION")
                .data(notification, MediaType.APPLICATION_JSON));
      } catch (IOException e) {
        log.warn("Failed to push SSE notification to user {}: {}", userId, e.getMessage());
        removeEmitter(userId, emitter);
      }
    }
  }

  /**
   * Heartbeat to keep HTTP connections alive through reverse proxies and gateways.
   */
  @Scheduled(fixedRate = 25000)
  public void sendKeepaliveHeartbeat() {
    for (Map.Entry<UUID, List<SseEmitter>> entry : userEmitters.entrySet()) {
      UUID userId = entry.getKey();
      for (SseEmitter emitter : entry.getValue()) {
        try {
          emitter.send(SseEmitter.event().comment("keepalive"));
        } catch (IOException e) {
          log.debug("Removing dead emitter on heartbeat for user {}", userId);
          removeEmitter(userId, emitter);
        }
      }
    }
  }

  private void removeEmitter(UUID userId, SseEmitter emitter) {
    List<SseEmitter> emitters = userEmitters.get(userId);
    if (emitters != null) {
      emitters.remove(emitter);
      if (emitters.isEmpty()) {
        userEmitters.remove(userId);
      }
    }
  }
}

