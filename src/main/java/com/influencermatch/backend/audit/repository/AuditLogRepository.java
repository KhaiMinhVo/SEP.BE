package com.influencermatch.backend.audit.repository;

import com.influencermatch.backend.audit.model.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

public interface AuditLogRepository
    extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {}
