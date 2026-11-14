package com.influencermatch.backend.audit.service;

import com.influencermatch.backend.audit.dto.AuditLogResponseDto;
import com.influencermatch.backend.audit.model.AuditLog;
import com.influencermatch.backend.audit.repository.AuditLogRepository;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.exception.ValidationException;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogQueryService {
  public static final int MAX_EXPORT_ROWS = 10_000;

  private final AuditLogRepository logs;

  public PageResponse<AuditLogResponseDto> list(
      UUID actorId,
      String action,
      String entityType,
      UUID entityId,
      LocalDateTime from,
      LocalDateTime to,
      String keyword,
      int page,
      int size) {
    Page<AuditLogResponseDto> result =
        logs.findAll(
                filter(actorId, action, entityType, entityId, from, to, keyword),
                PageRequest.of(
                    Math.max(0, page),
                    Math.min(100, Math.max(1, size)),
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
            .map(AuditLogResponseDto::from);
    return PageResponse.from(result);
  }

  public CsvExport export(
      UUID actorId,
      String action,
      String entityType,
      UUID entityId,
      LocalDateTime from,
      LocalDateTime to,
      String keyword,
      int limit) {
    int cappedLimit = Math.min(MAX_EXPORT_ROWS, Math.max(1, limit));
    List<AuditLog> rows =
        logs.findAll(
                filter(actorId, action, entityType, entityId, from, to, keyword),
                PageRequest.of(
                    0,
                    cappedLimit,
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
            .getContent();

    StringBuilder csv =
        new StringBuilder("id,actorId,action,entityType,entityId,timestamp\r\n");
    for (AuditLog row : rows) {
      csv.append(cell(row.getId()))
          .append(',')
          .append(cell(row.getUserId()))
          .append(',')
          .append(cell(row.getAction()))
          .append(',')
          .append(cell(row.getEntityType()))
          .append(',')
          .append(cell(row.getEntityId()))
          .append(',')
          .append(cell(row.getCreatedAt()))
          .append("\r\n");
    }
    return new CsvExport(csv.toString().getBytes(StandardCharsets.UTF_8), rows.size(), cappedLimit);
  }

  private Specification<AuditLog> filter(
      UUID actorId,
      String action,
      String entityType,
      UUID entityId,
      LocalDateTime from,
      LocalDateTime to,
      String keyword) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (actorId != null) predicates.add(cb.equal(root.get("userId"), actorId));
      if (action != null && !action.isBlank()) predicates.add(cb.equal(root.get("action"), action));
      if (entityType != null && !entityType.isBlank())
        predicates.add(cb.equal(root.get("entityType"), entityType));
      if (entityId != null) predicates.add(cb.equal(root.get("entityId"), entityId));
      if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
      if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
      addKeywordPredicate(root, cb, keyword, predicates);
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }

  private static void addKeywordPredicate(
      jakarta.persistence.criteria.Root<AuditLog> root,
      jakarta.persistence.criteria.CriteriaBuilder cb,
      String keyword,
      List<Predicate> predicates) {
    if (keyword == null || keyword.isBlank()) return;

    String value = keyword.trim();
    if (value.length() > 200) {
      throw new ValidationException("keyword must not exceed 200 characters");
    }
    String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    String pattern = "%" + escaped.toLowerCase(java.util.Locale.ROOT) + "%";
    Predicate textMatch =
        cb.or(
            cb.like(cb.lower(root.get("action")), pattern, '\\'),
            cb.like(cb.lower(root.get("entityType")), pattern, '\\'),
            cb.like(cb.lower(root.get("description")), pattern, '\\'),
            cb.like(cb.lower(root.get("ipAddress")), pattern, '\\'));
    UUID id;
    try {
      id = UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      predicates.add(textMatch);
      return;
    }
    predicates.add(
        cb.or(
            textMatch,
            cb.equal(root.get("id"), id),
            cb.equal(root.get("userId"), id),
            cb.equal(root.get("entityId"), id)));
  }

  private static String cell(Object value) {
    if (value == null) return "";
    String text = value.toString();
    if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }

  public record CsvExport(byte[] content, int rowCount, int limit) {}
}
