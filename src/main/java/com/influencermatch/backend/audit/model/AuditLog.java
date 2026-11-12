package com.influencermatch.backend.audit.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID userId;

  @Column(nullable = false, length = 100)
  private String action;

  @Column(nullable = false, length = 100)
  private String entityType;

  private UUID entityId;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode oldValue;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode newValue;

  @Column(length = Integer.MAX_VALUE)
  private String description;

  @Column(length = 64)
  private String ipAddress;

  @Column(nullable = false)
  private LocalDateTime createdAt;
}
