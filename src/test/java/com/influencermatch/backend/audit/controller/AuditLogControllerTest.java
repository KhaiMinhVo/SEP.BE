package com.influencermatch.backend.audit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.audit.service.AuditLogQueryService;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.exception.ForbiddenException;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class AuditLogControllerTest {
  private final AuditLogQueryService service = mock(AuditLogQueryService.class);
  private final AuditLogController controller = new AuditLogController(service);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void listPassesExistingFiltersAndKeywordToService() {
    TestActors.authenticate(TestActors.user(Role.ADMIN));
    UUIDFixture fixture = new UUIDFixture();
    when(service.list(
            fixture.actorId,
            "CHANGE_USER_STATUS",
            "User",
            fixture.entityId,
            fixture.from,
            fixture.to,
            "status",
            1,
            25))
        .thenReturn(new PageResponse<>(List.of(), 1, 25, 0, 0));

    var response =
        controller.list(
            fixture.actorId,
            "CHANGE_USER_STATUS",
            "User",
            fixture.entityId,
            fixture.from,
            fixture.to,
            "status",
            1,
            25);

    assertThat(response.data().page()).isEqualTo(1);
    verify(service)
        .list(
            fixture.actorId,
            "CHANGE_USER_STATUS",
            "User",
            fixture.entityId,
            fixture.from,
            fixture.to,
            "status",
            1,
            25);
  }

  @Test
  void exportRequiresAuditPermission() {
    TestActors.authenticate(TestActors.user(Role.DATA_MANAGER));

    assertThatThrownBy(
            () ->
                controller.export(
                    null, null, null, null, null, null, null, 1000))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(service);
  }

  @Test
  void controllerRequiresAuditPermission() {
    TestActors.authenticate(TestActors.user(Role.BRAND));
    assertThatThrownBy(() -> controller.list(null, null, null, null, null, null, null, 0, 20))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(service);
  }

  private static final class UUIDFixture {
    private final java.util.UUID actorId = java.util.UUID.randomUUID();
    private final java.util.UUID entityId = java.util.UUID.randomUUID();
    private final LocalDateTime from = LocalDateTime.now().minusDays(1);
    private final LocalDateTime to = LocalDateTime.now();
  }
}
