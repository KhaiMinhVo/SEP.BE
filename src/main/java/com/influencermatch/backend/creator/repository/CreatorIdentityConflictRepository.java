package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.model.CreatorIdentityConflict;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CreatorIdentityConflictRepository
    extends JpaRepository<CreatorIdentityConflict, UUID>,
        JpaSpecificationExecutor<CreatorIdentityConflict> {

  List<CreatorIdentityConflict> findByStatus(String status);

  List<CreatorIdentityConflict> findBySourceAccountId(UUID sourceAccountId);

  List<CreatorIdentityConflict> findByTargetMasterId(UUID targetMasterId);
}
