package com.influencermatch.backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.notification.dto.NotificationDto;
import com.influencermatch.backend.notification.service.NotificationService;
import com.influencermatch.backend.notification.service.RealtimeNotificationService;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationControllerTest {

  private final NotificationService notificationService = mock(NotificationService.class);
  private final RealtimeNotificationService realtimeNotificationService = mock(RealtimeNotificationService.class);
  private final NotificationController controller =
      new NotificationController(notificationService, realtimeNotificationService);

  @AfterEach
  void cleanContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void authenticatedBrandCanGetNotifications() {
    User actor = TestActors.user(Role.BRAND);
    TestActors.authenticate(actor);

    NotificationDto dto =
        NotificationDto.builder()
            .id(UUID.randomUUID())
            .userId(actor.getId())
            .type("SYSTEM")
            .title("Welcome")
            .message("Welcome to platform")
            .createdAt(LocalDateTime.now())
            .build();

    when(notificationService.getUserNotifications(eq(actor.getId()), eq(0), eq(20)))
        .thenReturn(new PageResponse<>(List.of(dto), 0, 20, 1, 1));

    ResponseEntity<ApiResponse<PageResponse<NotificationDto>>> response =
        controller.getNotifications(0, 20);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody().data().items()).hasSize(1);
    assertThat(response.getBody().data().items().get(0).getTitle()).isEqualTo("Welcome");
  }

  @Test
  void authenticatedBrandCanGetUnreadCount() {
    User actor = TestActors.user(Role.BRAND);
    TestActors.authenticate(actor);

    when(notificationService.getUnreadCount(actor.getId())).thenReturn(7L);

    ResponseEntity<ApiResponse<Map<String, Long>>> response = controller.getUnreadCount();

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody().data().get("unreadCount")).isEqualTo(7L);
  }

  @Test
  void authenticatedCreatorCanMarkAsRead() {
    User actor = TestActors.user(Role.CREATOR);
    TestActors.authenticate(actor);
    UUID notificationId = UUID.randomUUID();

    NotificationDto dto =
        NotificationDto.builder()
            .id(notificationId)
            .userId(actor.getId())
            .isRead(true)
            .readAt(LocalDateTime.now())
            .build();

    when(notificationService.markAsRead(actor.getId(), notificationId)).thenReturn(dto);

    ResponseEntity<ApiResponse<NotificationDto>> response = controller.markAsRead(notificationId);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody().data().isRead()).isTrue();
  }

  @Test
  void authenticatedCreatorCanMarkAllAsRead() {
    User actor = TestActors.user(Role.CREATOR);
    TestActors.authenticate(actor);

    when(notificationService.markAllAsRead(actor.getId())).thenReturn(3);

    ResponseEntity<ApiResponse<Map<String, Integer>>> response = controller.markAllAsRead();

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody().data().get("markedCount")).isEqualTo(3);
  }

  @Test
  void authenticatedUserCanSubscribeToSseStream() {
    User actor = TestActors.user(Role.BRAND);
    TestActors.authenticate(actor);

    SseEmitter emitter = new SseEmitter();
    when(realtimeNotificationService.subscribe(actor.getId())).thenReturn(emitter);

    SseEmitter result = controller.subscribeStream();

    assertThat(result).isSameAs(emitter);
  }

  @Test
  void unauthenticatedUserCannotAccessNotifications() {
    assertThatThrownBy(() -> controller.getNotifications(0, 20))
        .isInstanceOf(BusinessException.class);
  }
}
