package com.influencermatch.backend.notification.service;

import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.exception.NotFoundException;
import com.influencermatch.backend.notification.dto.NotificationDto;
import com.influencermatch.backend.notification.enums.NotificationType;
import com.influencermatch.backend.notification.model.Notification;
import com.influencermatch.backend.notification.repository.NotificationRepository;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Luồng 15.1: Core notification service implementing robust in-transaction persistence
 * with AFTER_COMMIT realtime SSE and async email dispatch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

  private final NotificationRepository notificationRepository;
  private final RealtimeNotificationService realtimeNotificationService;
  private final NotificationEmailSender notificationEmailSender;
  private final UserRepository userRepository;

  /**
   * Policy-driven notification dispatch resolving channels (In-App / Email) via NotificationType.
   * Enforces transactional integrity and prevents duplicate dispatches.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public NotificationDto createAndDispatch(
      UUID userId,
      NotificationType type,
      String title,
      String message,
      String entityType,
      UUID entityId) {
    return createAndDispatch(
        userId,
        type,
        title,
        message,
        entityType,
        entityId,
        type.getOccurrencePolicy() == com.influencermatch.backend.notification.enums.OccurrencePolicy.SINGLE_PER_ENTITY);
  }

  /**
   * Overloaded policy-driven dispatch with optional deduplication.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public NotificationDto createAndDispatch(
      UUID userId,
      NotificationType type,
      String title,
      String message,
      String entityType,
      UUID entityId,
      boolean deduplicate) {

    // 1. Transaction Guard: Require both active physical DB transaction and synchronization context
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Notification dispatch must occur within an active database transaction context");
    }

    // 2. Idempotency / Deduplication Guard
    boolean shouldDedup =
        deduplicate
            && (type.getOccurrencePolicy()
                == com.influencermatch.backend.notification.enums.OccurrencePolicy.SINGLE_PER_ENTITY);
    if (shouldDedup
        && entityId != null
        && notificationRepository.existsByUserIdAndTypeAndEntityTypeAndEntityId(
            userId, type.name(), entityType, entityId)) {
      log.debug(
          "Idempotency hit: duplicate notification skipped for user={}, type={}, entityType={}, entityId={}",
          userId,
          type,
          entityType,
          entityId);
      return notificationRepository
          .findFirstByUserIdAndTypeAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
              userId, type.name(), entityType, entityId)
          .map(NotificationDto::fromEntity)
          .orElse(null);
    }

    // 3. Centralized Channel Policy
    boolean sendEmail = type.isEmailDispatchEnabled();
    String resolvedTitle = (title != null && !title.isBlank()) ? title : type.getDefaultTitle();
    String resolvedMessage = (message != null && !message.isBlank()) ? message : type.getDefaultMessage();

    Notification notification =
        Notification.builder()
            .userId(userId)
            .type(type.name())
            .title(resolvedTitle)
            .message(resolvedMessage)
            .entityType(entityType)
            .entityId(entityId)
            .isRead(false)
            .createdAt(LocalDateTime.now())
            .build();

    Notification saved = notificationRepository.save(notification);
    NotificationDto dto = NotificationDto.fromEntity(saved);

    dispatchPostCommit(userId, resolvedTitle, resolvedMessage, dto, sendEmail);

    return dto;
  }

  /**
   * Persists the notification record within the current business transaction,
   * then schedules SSE & email delivery strictly after the transaction commits.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public NotificationDto createAndDispatch(
      UUID userId,
      String type,
      String title,
      String message,
      String entityType,
      UUID entityId,
      boolean sendEmail) {

    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Notification dispatch must occur within an active database transaction context");
    }

    Notification notification =
        Notification.builder()
            .userId(userId)
            .type(type)
            .title(title)
            .message(message)
            .entityType(entityType)
            .entityId(entityId)
            .isRead(false)
            .createdAt(LocalDateTime.now())
            .build();

    Notification saved = notificationRepository.save(notification);
    NotificationDto dto = NotificationDto.fromEntity(saved);

    dispatchPostCommit(userId, title, message, dto, sendEmail);

    return dto;
  }

  private void dispatchPostCommit(
      UUID userId, String title, String message, NotificationDto dto, boolean sendEmail) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              triggerDispatch(userId, title, message, dto, sendEmail);
            }
          });
    } else {
      triggerDispatch(userId, title, message, dto, sendEmail);
    }
  }

  private void triggerDispatch(
      UUID userId, String title, String message, NotificationDto dto, boolean sendEmail) {
    try {
      realtimeNotificationService.sendNotification(userId, dto);
    } catch (Exception e) {
      log.warn("Failed to push realtime SSE notification for user {}: {}", userId, e.getMessage());
    }

    if (sendEmail) {
      userRepository
          .findById(userId)
          .ifPresent(
              u -> notificationEmailSender.sendEmailAsync(u.getEmail(), title, message));
    }
  }

  @Transactional(readOnly = true)
  public PageResponse<NotificationDto> getUserNotifications(UUID userId, int page, int size) {
    Page<Notification> p =
        notificationRepository.findByUserIdOrderByCreatedAtDesc(
            userId, PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size))));
    return new PageResponse<>(
        p.getContent().stream().map(NotificationDto::fromEntity).toList(),
        p.getNumber(),
        p.getSize(),
        p.getTotalElements(),
        p.getTotalPages());
  }

  @Transactional(readOnly = true)
  public long getUnreadCount(UUID userId) {
    return notificationRepository.countByUserIdAndIsReadFalse(userId);
  }

  @Transactional
  public NotificationDto markAsRead(UUID userId, UUID notificationId) {
    Notification notification =
        notificationRepository
            .findByIdAndUserId(notificationId, userId)
            .orElseThrow(
                () -> new NotFoundException("Notification not found"));

    if (!notification.isRead()) {
      notification.setRead(true);
      notification.setReadAt(LocalDateTime.now());
      notification = notificationRepository.save(notification);
    }

    return NotificationDto.fromEntity(notification);
  }

  @Transactional
  public int markAllAsRead(UUID userId) {
    return notificationRepository.markAllAsRead(userId, LocalDateTime.now());
  }
}

