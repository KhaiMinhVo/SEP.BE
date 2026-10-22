package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.model.CreatorIdentityAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CreatorIdentityAuditRepository
    extends JpaRepository<CreatorIdentityAudit, UUID>,
        JpaSpecificationExecutor<CreatorIdentityAudit> {

  List<CreatorIdentityAudit> findByMasterIdOrderByCreatedAtDesc(UUID masterId);

  List<CreatorIdentityAudit> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
