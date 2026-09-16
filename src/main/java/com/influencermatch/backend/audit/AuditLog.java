package com.influencermatch.backend.audit;
import com.fasterxml.jackson.databind.JsonNode; import com.influencermatch.backend.entity.User; import jakarta.persistence.*; import lombok.*; import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="auditLog") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class AuditLog {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="auditLogId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="userId") private User user;
 @Column(nullable=false,length=100) private String action; @Column(nullable=false,length=100) private String entityType; private UUID entityId;
 @JdbcTypeCode(SqlTypes.JSON) private JsonNode oldValue; @JdbcTypeCode(SqlTypes.JSON) private JsonNode newValue;
 private String description; @Column(length=64) private String ipAddress; @Column(nullable=false) private LocalDateTime createdAt;
}
