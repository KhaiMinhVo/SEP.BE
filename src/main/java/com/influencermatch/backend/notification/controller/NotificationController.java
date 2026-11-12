package com.influencermatch.backend.notification.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.notification.dto.NotificationDto;
import com.influencermatch.backend.notification.service.NotificationService;
import com.influencermatch.backend.notification.service.RealtimeNotificationService;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Luồng 15.1: Bắn thông báo Realtime & Điều phối Email (Notification Dispatcher).
 * Endpoints for in-app notification inbox, unread counters, and realtime SSE stream.
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Tag(
    name = "Notifications",
    description = "VIEW_OWN_NOTIFICATION / MANAGE_OWN_NOTIFICATION; Notification inbox & Realtime SSE")
public class NotificationController {

  private final NotificationService notificationService;
  private final RealtimeNotificationService realtimeNotificationService;

  @GetMapping
  @PreAuthorize("hasAuthority('VIEW_OWN_NOTIFICATION')")
  @Operation(
      summary = "Get user notifications",
      description = "Returns paginated notifications for the authenticated user ordered by createdAt descending.",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<PageResponse<NotificationDto>>> getNotifications(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Permissions.require(Permission.VIEW_OWN_NOTIFICATION);
    User actor = Permissions.actor();
    return ResponseEntity.ok(ApiResponse.ok(notificationService.getUserNotifications(actor.getId(), page, size)));
  }

  @GetMapping("/unread-count")
  @PreAuthorize("hasAuthority('VIEW_OWN_NOTIFICATION')")
  @Operation(
      summary = "Get unread notifications count",
      description = "Returns the count of unread notifications for badge rendering.",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount() {
    Permissions.require(Permission.VIEW_OWN_NOTIFICATION);
    User actor = Permissions.actor();
    long count = notificationService.getUnreadCount(actor.getId());
    return ResponseEntity.ok(ApiResponse.ok(Map.of("unreadCount", count)));
  }

  @PatchMapping("/{id}/read")
  @PreAuthorize("hasAuthority('MANAGE_OWN_NOTIFICATION')")
  @Operation(
      summary = "Mark single notification as read",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<NotificationDto>> markAsRead(@PathVariable UUID id) {
    Permissions.require(Permission.MANAGE_OWN_NOTIFICATION);
    User actor = Permissions.actor();
    return ResponseEntity.ok(ApiResponse.ok(notificationService.markAsRead(actor.getId(), id)));
  }

  @PatchMapping("/read-all")
  @PreAuthorize("hasAuthority('MANAGE_OWN_NOTIFICATION')")
  @Operation(
      summary = "Mark all notifications as read",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllAsRead() {
    Permissions.require(Permission.MANAGE_OWN_NOTIFICATION);
    User actor = Permissions.actor();
    int count = notificationService.markAllAsRead(actor.getId());
    return ResponseEntity.ok(ApiResponse.ok(Map.of("markedCount", count)));
  }

  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @PreAuthorize("hasAuthority('VIEW_OWN_NOTIFICATION')")
  @Operation(
      summary = "Subscribe to personal realtime notification stream (SSE)",
      description = "Opens a long-lived Server-Sent Events stream delivering notifications in real-time.",
      security = @SecurityRequirement(name = "BearerAuth"))
  public SseEmitter subscribeStream() {
    Permissions.require(Permission.VIEW_OWN_NOTIFICATION);
    User actor = Permissions.actor();
    return realtimeNotificationService.subscribe(actor.getId());
  }
}

