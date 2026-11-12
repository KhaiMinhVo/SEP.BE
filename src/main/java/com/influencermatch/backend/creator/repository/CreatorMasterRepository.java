package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.model.CreatorMaster;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CreatorMasterRepository
    extends JpaRepository<CreatorMaster, UUID>, JpaSpecificationExecutor<CreatorMaster> {

  Optional<CreatorMaster> findByUserId(UUID userId);

  boolean existsByUserId(UUID userId);
}
