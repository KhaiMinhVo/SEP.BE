package com.influencermatch.backend.audit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.influencermatch.backend.audit.model.AuditLog;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public record AuditLogResponseDto(
    UUID id,
    UUID actorId,
    UUID userId,
    String action,
    String entityType,
    UUID entityId,
    LocalDateTime timestamp,
    LocalDateTime createdAt,
    String ipAddress,
    JsonNode oldValue,
    JsonNode newValue) {

  private static final Set<String> SAFE_METADATA_FIELDS =
      Set.of("status", "role", "enabled", "permission", "version", "revision", "profileRevision");

  public static AuditLogResponseDto from(AuditLog log) {
    return new AuditLogResponseDto(
        log.getId(),
        log.getUserId(),
        log.getUserId(),
        log.getAction(),
        log.getEntityType(),
        log.getEntityId(),
        log.getCreatedAt(),
        log.getCreatedAt(),
        log.getIpAddress(),
        safeMetadata(log.getOldValue()),
        safeMetadata(log.getNewValue()));
  }

  private static JsonNode safeMetadata(JsonNode source) {
    if (source == null || !source.isObject()) return null;
    var safe = JsonNodeFactory.instance.objectNode();
    source
        .fields()
        .forEachRemaining(
            entry -> {
              if (!SAFE_METADATA_FIELDS.contains(entry.getKey())) return;
              JsonNode value = entry.getValue();
              if (value.isTextual() || value.isBoolean() || value.isNumber()) {
                safe.set(entry.getKey(), value.deepCopy());
              }
            });
    return safe.isEmpty() ? null : safe;
  }
}
