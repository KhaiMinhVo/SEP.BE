package com.influencermatch.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.notification.dto.NotificationDto;
import com.influencermatch.backend.notification.enums.NotificationType;
import com.influencermatch.backend.notification.model.Notification;
import com.influencermatch.backend.notification.repository.NotificationRepository;
import com.influencermatch.backend.notification.service.NotificationEmailSender;
import com.influencermatch.backend.notification.service.NotificationService;
import com.influencermatch.backend.notification.service.RealtimeNotificationService;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationServiceTest {

  private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
  private final RealtimeNotificationService realtimeNotificationService = mock(RealtimeNotificationService.class);
  private final NotificationEmailSender notificationEmailSender = mock(NotificationEmailSender.class);
  private final UserRepository userRepository = mock(UserRepository.class);

  private final NotificationService service =
      new NotificationService(
          notificationRepository,
          realtimeNotificationService,
          notificationEmailSender,
          userRepository);

  @BeforeEach
  void setUp() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void tearDown() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void throwsExceptionWhenCalledOutsideActiveTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.clearSynchronization();
    UUID userId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service.createAndDispatch(
                    userId,
                    NotificationType.COLLABORATION_PROPOSAL,
                    "Title",
                    "Message",
                    "COLLABORATION",
                    UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active database transaction context");
  }

  @Test
  void throwsExceptionWhenSynchronizationActiveButNoActualTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    // synchronization is active, but actual transaction is false
    UUID userId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service.createAndDispatch(
                    userId,
                    NotificationType.COLLABORATION_PROPOSAL,
                    "Title",
                    "Message",
                    "COLLABORATION",
                    UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active database transaction context");
  }

  @Test
  void policyDrivenProposalAutomaticallyDispatchesEmailAndSse() {
    UUID userId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();

    when(notificationRepository.save(any(Notification.class)))
        .thenAnswer(
            inv -> {
              Notification n = inv.getArgument(0);
              n.setId(UUID.randomUUID());
              return n;
            });

    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).email("creator@example.com").build()));

    NotificationDto result =
        service.createAndDispatch(
            userId,
            NotificationType.COLLABORATION_PROPOSAL,
            "Đề xuất hợp tác",
            "Mời bạn tham gia chiến dịch",
            "COLLABORATION",
            entityId);

    assertThat(result).isNotNull();
    assertThat(result.getTitle()).isEqualTo("Đề xuất hợp tác");
    assertThat(result.getType()).isEqualTo("COLLABORATION_PROPOSAL");

    // Verify entity was saved
    ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(captor.capture());
    assertThat(captor.getValue().getType()).isEqualTo("COLLABORATION_PROPOSAL");
  }

  @Test
  void policyDrivenInAppOnlyEventDoesNotDispatchEmail() {
    UUID userId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();

    when(notificationRepository.save(any(Notification.class)))
        .thenAnswer(
            inv -> {
              Notification n = inv.getArgument(0);
              n.setId(UUID.randomUUID());
              return n;
            });

    NotificationDto result =
        service.createAndDispatch(
            userId,
            NotificationType.CONTENT_DRAFT_APPROVED,
            "Duyệt bản nháp",
            "Nhãn hàng đã duyệt bản nháp của bạn",
            "COLLABORATION",
            entityId);

    assertThat(result).isNotNull();
    assertThat(result.getType()).isEqualTo("CONTENT_DRAFT_APPROVED");

    // Email sender must never be called for In-App only events
    verifyNoInteractions(notificationEmailSender);
  }

  @Test
  void deduplicationSkipsDuplicateNotification() {
    UUID userId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();

    when(notificationRepository.existsByUserIdAndTypeAndEntityTypeAndEntityId(
            userId, "PAYMENT_SUCCESS", "PAYMENT", entityId))
        .thenReturn(true);

    Notification existing =
        Notification.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .type("PAYMENT_SUCCESS")
            .title("Xác nhận thanh toán")
            .message("Thành công")
            .build();

    when(notificationRepository
            .findFirstByUserIdAndTypeAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
                userId, "PAYMENT_SUCCESS", "PAYMENT", entityId))
        .thenReturn(Optional.of(existing));

    NotificationDto result =
        service.createAndDispatch(
            userId,
            NotificationType.PAYMENT_SUCCESS,
            "Xác nhận thanh toán",
            "Thành công",
            "PAYMENT",
            entityId,
            true);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(existing.getId());

    // Never save duplicate
    verify(notificationRepository, never()).save(any(Notification.class));
  }

  @Test
  void multiEventInstanceAllowsMultipleDifferentAssetsOnSameWorkflow() {
    UUID userId = UUID.randomUUID();
    UUID asset1Id = UUID.randomUUID();
    UUID asset2Id = UUID.randomUUID();

    when(notificationRepository.save(any(Notification.class)))
        .thenAnswer(
            inv -> {
              Notification n = inv.getArgument(0);
              n.setId(UUID.randomUUID());
              return n;
            });

    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).email("brand@example.com").build()));

    // First asset submission
    NotificationDto notif1 =
        service.createAndDispatch(
            userId,
            NotificationType.EVIDENCE_SUBMITTED,
            "Creator đã nộp bài nghiệm thu #1",
            "Nội dung 1",
            "EVIDENCE_ASSET",
            asset1Id);

    // Second asset submission on same collaboration
    NotificationDto notif2 =
        service.createAndDispatch(
            userId,
            NotificationType.EVIDENCE_SUBMITTED,
            "Creator đã nộp bài nghiệm thu #2",
            "Nội dung 2",
            "EVIDENCE_ASSET",
            asset2Id);

    assertThat(notif1).isNotNull();
    assertThat(notif2).isNotNull();
    assertThat(notif1.getEntityId()).isEqualTo(asset1Id);
    assertThat(notif2.getEntityId()).isEqualTo(asset2Id);
    verify(notificationRepository, times(2)).save(any(Notification.class));
  }

  @Test
  void getUserNotificationsReturnsPaginatedDtos() {
    UUID userId = UUID.randomUUID();
    Notification n =
        Notification.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .type("SYSTEM")
            .title("Update")
            .message("System updated")
            .createdAt(LocalDateTime.now())
            .build();

    when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class)))
        .thenReturn(new PageImpl<>(List.of(n), PageRequest.of(0, 10), 1));

    PageResponse<NotificationDto> page = service.getUserNotifications(userId, 0, 10);

    assertThat(page.totalElements()).isEqualTo(1);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).getTitle()).isEqualTo("Update");
  }

  @Test
  void markAsReadUpdatesReadFlagAndTimestamp() {
    UUID userId = UUID.randomUUID();
    UUID notificationId = UUID.randomUUID();
    Notification n =
        Notification.builder()
            .id(notificationId)
            .userId(userId)
            .type("INVITE")
            .title("Invitation")
            .message("Invited")
            .isRead(false)
            .createdAt(LocalDateTime.now())
            .build();

    when(notificationRepository.findByIdAndUserId(notificationId, userId))
        .thenReturn(Optional.of(n));
    when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

    NotificationDto result = service.markAsRead(userId, notificationId);

    assertThat(result.isRead()).isTrue();
    assertThat(result.getReadAt()).isNotNull();
    verify(notificationRepository).save(n);
  }

  @Test
  void markAllAsReadDelegatesToRepository() {
    UUID userId = UUID.randomUUID();
    when(notificationRepository.markAllAsRead(eq(userId), any(LocalDateTime.class)))
        .thenReturn(5);

    int updatedCount = service.markAllAsRead(userId);

    assertThat(updatedCount).isEqualTo(5);
    verify(notificationRepository).markAllAsRead(eq(userId), any(LocalDateTime.class));
  }
}
