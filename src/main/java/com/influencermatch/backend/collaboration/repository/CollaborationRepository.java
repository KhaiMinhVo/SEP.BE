package com.influencermatch.backend.collaboration.repository;

import com.influencermatch.backend.collaboration.model.Collaboration;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CollaborationRepository extends JpaRepository<Collaboration, UUID> {
  boolean existsByRelationshipId(UUID relationshipId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Collaboration c where c.id = :id")
  Optional<Collaboration> findLockedById(@Param("id") UUID id);
}
