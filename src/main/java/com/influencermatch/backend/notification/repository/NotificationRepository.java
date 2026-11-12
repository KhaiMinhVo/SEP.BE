package com.influencermatch.backend.notification.repository;

import com.influencermatch.backend.notification.model.Notification;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  long countByUserIdAndIsReadFalse(UUID userId);

  Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

  boolean existsByUserIdAndTypeAndEntityTypeAndEntityId(
      UUID userId, String type, String entityType, UUID entityId);

  Optional<Notification> findFirstByUserIdAndTypeAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
      UUID userId, String type, String entityType, UUID entityId);

  @Modifying
  @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :readAt WHERE n.userId = :userId AND n.isRead = false")
  int markAllAsRead(@Param("userId") UUID userId, @Param("readAt") LocalDateTime readAt);
}

