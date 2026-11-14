package com.influencermatch.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.audit.model.AuditLog;
import com.influencermatch.backend.audit.repository.AuditLogRepository;
import com.influencermatch.backend.audit.service.AuditLogQueryService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class AuditLogQueryServiceTest {
  @Mock private AuditLogRepository logs;

  @InjectMocks private AuditLogQueryService service;

  @Test
  void listReturnsDtoWithOnlyAllowlistedMetadataAndKeepsActorId() throws Exception {
    UUID actorId = UUID.randomUUID();
    var mapper = new ObjectMapper();
    AuditLog log =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .userId(actorId)
            .action("CHANGE_USER_STATUS")
            .entityType("User")
            .entityId(UUID.randomUUID())
            .createdAt(LocalDateTime.now())
            .ipAddress("192.0.2.1")
            .description("private-description")
            .oldValue(mapper.readTree("{\"status\":\"ACTIVE\",\"password\":\"secret\"}"))
            .newValue(mapper.readTree("{\"status\":\"LOCKED\",\"email\":\"private@example.test\"}"))
            .build();
    when(logs.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(java.util.List.of(log)));

    var page = service.list(actorId, "CHANGE_USER_STATUS", "User", null, null, null, null, 0, 20);
    var item = page.items().getFirst();

    assertThat(page.totalElements()).isEqualTo(1);
    assertThat(item.actorId()).isEqualTo(actorId);
    assertThat(item.userId()).isEqualTo(actorId);
    assertThat(item.oldValue().path("status").asText()).isEqualTo("ACTIVE");
    assertThat(item.oldValue().has("password")).isFalse();
    assertThat(item.newValue().has("email")).isFalse();
    assertThat(item.timestamp()).isEqualTo(log.getCreatedAt());
    verify(logs).findAll(any(Specification.class), any(Pageable.class));
  }

  @Test
  void exportCapsRowsAndDoesNotIncludePayloadOrPersonalMetadata() throws Exception {
    var mapper = new ObjectMapper();
    AuditLog log =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .action("CHANGE_USER_STATUS")
            .entityType("User")
            .entityId(UUID.randomUUID())
            .createdAt(LocalDateTime.now())
            .ipAddress("192.0.2.1")
            .description("private-description")
            .oldValue(mapper.readTree("{\"password\":\"csv-secret\"}"))
            .newValue(mapper.readTree("{\"email\":\"csv-private@example.test\"}"))
            .build();
    when(logs.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(java.util.List.of(log)));

    var result = service.export(null, null, null, null, null, null, "status", 100_000);

    assertThat(result.limit()).isEqualTo(AuditLogQueryService.MAX_EXPORT_ROWS);
    assertThat(result.rowCount()).isEqualTo(1);
    String csv = new String(result.content(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(csv).contains(log.getId().toString(), log.getUserId().toString(), "CHANGE_USER_STATUS");
    assertThat(csv)
        .doesNotContain("csv-secret", "csv-private@example.test", "private-description", "192.0.2.1");
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(logs).findAll(any(Specification.class), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(AuditLogQueryService.MAX_EXPORT_ROWS);
    assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
        .isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);
  }
}
