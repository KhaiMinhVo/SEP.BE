package com.influencermatch.backend.notification.dto;

import com.influencermatch.backend.notification.model.Notification;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDto {
  private UUID id;
  private UUID userId;
  private String type;
  private String title;
  private String message;
  private String entityType;
  private UUID entityId;
  private boolean isRead;
  private LocalDateTime readAt;
  private LocalDateTime createdAt;

  public static NotificationDto fromEntity(Notification entity) {
    if (entity == null) {
      return null;
    }
    return NotificationDto.builder()
        .id(entity.getId())
        .userId(entity.getUserId())
        .type(entity.getType())
        .title(entity.getTitle())
        .message(entity.getMessage())
        .entityType(entity.getEntityType())
        .entityId(entity.getEntityId())
        .isRead(entity.isRead())
        .readAt(entity.getReadAt())
        .createdAt(entity.getCreatedAt())
        .build();
  }
}

