package com.influencermatch.backend.audit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.audit.dto.SystemStatusResponseDto;
import com.influencermatch.backend.audit.service.SystemMonitoringService;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ForbiddenException;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

class SystemMonitoringControllerTest {

  private final SystemMonitoringService monitoringService = mock(SystemMonitoringService.class);
  private final SystemMonitoringController controller = new SystemMonitoringController(monitoringService);

  @AfterEach
  void cleanContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void adminCanAccessSystemStatus() {
    TestActors.authenticate(TestActors.user(Role.ADMIN));
    SystemStatusResponseDto mockStatus =
        SystemStatusResponseDto.builder()
            .overallStatus("HEALTHY")
            .timestamp(Instant.now())
            .jvm(
                SystemStatusResponseDto.JvmMetrics.builder()
                    .uptimeMs(12345L)
                    .totalMemoryBytes(1000000L)
                    .freeMemoryBytes(500000L)
                    .usedMemoryBytes(500000L)
                    .availableProcessors(4)
                    .build())
            .build();

    when(monitoringService.getSystemStatus()).thenReturn(mockStatus);

    ResponseEntity<ApiResponse<SystemStatusResponseDto>> response = controller.getSystemStatus();

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody().data().getOverallStatus()).isEqualTo("HEALTHY");
    assertThat(response.getBody().data().getJvm().getUptimeMs()).isEqualTo(12345L);
  }

  @Test
  void brandCannotAccessSystemStatus() {
    TestActors.authenticate(TestActors.user(Role.BRAND));

    assertThatThrownBy(controller::getSystemStatus)
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void unauthenticatedCannotAccessSystemStatus() {
    assertThatThrownBy(controller::getSystemStatus)
        .isInstanceOf(BusinessException.class);
  }
}
