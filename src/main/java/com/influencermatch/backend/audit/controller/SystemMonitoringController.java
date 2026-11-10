package com.influencermatch.backend.audit.controller;

import com.influencermatch.backend.audit.dto.SystemStatusResponseDto;
import com.influencermatch.backend.audit.service.SystemMonitoringService;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Luồng AD-02: Giám sát Hệ thống (System Monitoring).
 * Restricted to administrators with VIEW_SYSTEM_STATUS.
 */
@RestController
@RequestMapping("/admin/system-status")
@RequiredArgsConstructor
@Tag(
    name = "System Monitoring",
    description = "VIEW_SYSTEM_STATUS; Verifiable system runtime metrics and health")
public class SystemMonitoringController {

  private final SystemMonitoringService systemMonitoringService;

  @GetMapping
  @PreAuthorize("hasAuthority('VIEW_SYSTEM_STATUS')")
  @Operation(
      summary = "Get system health and runtime metrics",
      description = "Returns real measurable telemetry: JVM heap memory, uptime, database ping response time, and HikariCP connection pool usage.",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<SystemStatusResponseDto>> getSystemStatus() {
    Permissions.require(Permission.VIEW_SYSTEM_STATUS);
    return ResponseEntity.ok(ApiResponse.ok(systemMonitoringService.getSystemStatus()));
  }
}

