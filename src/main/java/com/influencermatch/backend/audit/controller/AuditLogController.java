package com.influencermatch.backend.audit.controller;

import com.influencermatch.backend.audit.model.AuditLog;
import com.influencermatch.backend.audit.repository.AuditLogRepository;
import com.influencermatch.backend.common.dto.*;
import com.influencermatch.backend.security.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "VIEW_AUDIT_LOG; administrator only")
public class AuditLogController {
  private final AuditLogRepository logs;

  @GetMapping
  @PreAuthorize("hasAuthority('VIEW_AUDIT_LOG')")
  @Operation(
      summary = "Read audit logs",
      description = "Filters are combined; page size is capped at 100.")
  public ApiResponse<PageResponse<AuditLog>> list(
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) UUID entityId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Permissions.require(Permission.VIEW_AUDIT_LOG);
    if (from != null && to != null && from.isAfter(to))
      throw new com.influencermatch.backend.exception.ValidationException("from must be before to");
    Specification<AuditLog> spec =
        (root, query, cb) -> {
          var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
          if (actorId != null) predicates.add(cb.equal(root.get("userId"), actorId));
          if (action != null) predicates.add(cb.equal(root.get("action"), action));
          if (entityType != null) predicates.add(cb.equal(root.get("entityType"), entityType));
          if (entityId != null) predicates.add(cb.equal(root.get("entityId"), entityId));
          if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
          if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
          return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    return ApiResponse.ok(
        PageResponse.from(
            logs.findAll(
                spec,
                PageRequest.of(
                    Math.max(0, page),
                    Math.min(100, Math.max(1, size)),
                    Sort.by(Sort.Direction.DESC, "createdAt")))));
  }
}
