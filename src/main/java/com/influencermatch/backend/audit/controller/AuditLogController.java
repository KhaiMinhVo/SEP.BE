package com.influencermatch.backend.audit.controller;

import com.influencermatch.backend.audit.dto.AuditLogResponseDto;
import com.influencermatch.backend.audit.service.AuditLogQueryService;
import com.influencermatch.backend.audit.service.AuditLogQueryService.CsvExport;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "VIEW_AUDIT_LOG; administrator only")
@SecurityRequirement(name = "BearerAuth")
@PreAuthorize("hasAuthority('VIEW_AUDIT_LOG')")
public class AuditLogController {
  private final AuditLogQueryService service;

  @GetMapping
  @Operation(
      summary = "Read audit logs",
      description =
          "Combines existing filters with keyword search over stored action, entityType, "
              + "description, IP address, and exact UUIDs. Page size is capped at 100; results "
              + "are newest first.")
  public ApiResponse<PageResponse<AuditLogResponseDto>> list(
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) UUID entityId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime to,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Permissions.require(Permission.VIEW_AUDIT_LOG);
    validateDateRange(from, to);
    return ApiResponse.ok(
        service.list(actorId, action, entityType, entityId, from, to, keyword, page, size));
  }

  @GetMapping(value = "/export", produces = "text/csv")
  @Operation(
      summary = "Export filtered audit log metadata",
      description =
          "Exports at most 10,000 rows. Export contains event identifiers and timestamps only; "
              + "it excludes actor names/emails, IP addresses, descriptions, and audit payloads.")
  public ResponseEntity<byte[]> export(
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) UUID entityId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime to,
      @RequestParam(required = false) String keyword,
      @Parameter(description = "Maximum rows, 1-10,000")
          @RequestParam(defaultValue = "1000")
          int limit) {
    Permissions.require(Permission.VIEW_AUDIT_LOG);
    validateDateRange(from, to);
    CsvExport export =
        service.export(actorId, action, entityType, entityId, from, to, keyword, limit);
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("audit-logs.csv").build().toString())
        .header("X-Export-Row-Limit", Integer.toString(export.limit()))
        .body(export.content());
  }

  private static void validateDateRange(LocalDateTime from, LocalDateTime to) {
    if (from != null && to != null && from.isAfter(to))
      throw new ValidationException("from must be before to");
  }
}
