package com.influencermatch.backend.notification.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Luồng 15.1: Durable in-app notification entity matching V1__init_schema.sql.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "type", nullable = false, length = 100)
  private String type;

  @Column(name = "title", nullable = false, length = 255)
  private String title;

  @Column(name = "message", nullable = false, columnDefinition = "TEXT")
  private String message;

  @Column(name = "entity_type", length = 100)
  private String entityType;

  @Column(name = "entity_id")
  private UUID entityId;

  @Column(name = "is_read", nullable = false)
  @Builder.Default
  private boolean isRead = false;

  @Column(name = "read_at")
  private LocalDateTime readAt;

  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  public void onPrePersist() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    if (this.createdAt == null) {
      this.createdAt = LocalDateTime.now();
    }
  }
}

