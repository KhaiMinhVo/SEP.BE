package com.influencermatch.backend.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.audit.model.AuditLog;
import com.influencermatch.backend.audit.repository.AuditLogRepository;
import com.influencermatch.backend.security.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.context.request.*;

@Service
@RequiredArgsConstructor
public class AuditService {
  private final AuditLogRepository logs;
  private final ObjectMapper mapper;

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      String action,
      String entityType,
      UUID entityId,
      Object oldValue,
      Object newValue,
      String reason) {
    var attrs = RequestContextHolder.getRequestAttributes();
    String ip =
        attrs instanceof ServletRequestAttributes servlet
            ? servlet.getRequest().getRemoteAddr()
            : null;
    logs.save(
        AuditLog.builder()
            .userId(Permissions.actor().getId())
            .action(action)
            .entityType(entityType)
            .entityId(entityId)
            .oldValue(mapper.valueToTree(oldValue))
            .newValue(mapper.valueToTree(newValue))
            .description(reason)
            .ipAddress(ip)
            .createdAt(LocalDateTime.now())
            .build());
  }
}
