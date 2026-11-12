package com.influencermatch.backend.collaboration.repository;

import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import com.influencermatch.backend.collaboration.model.Collaboration;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CollaborationRepository extends JpaRepository<Collaboration, UUID> {
  boolean existsByRelationshipId(UUID relationshipId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Collaboration c where c.id = :id")
  Optional<Collaboration> findLockedById(@Param("id") UUID id);

  Page<Collaboration> findByCampaignId(UUID campaignId, Pageable pageable);

  Page<Collaboration> findByCampaignIdAndStatus(
      UUID campaignId, CollaborationStatus status, Pageable pageable);

  @Query(
      "select c from Collaboration c where (c.master.id = :masterId or c.creator.master.id ="
          + " :masterId) order by c.updatedAt desc")
  List<Collaboration> findByMasterId(@Param("masterId") UUID masterId);

  @Query(
      "select c from Collaboration c where (c.master.id = :masterId or c.creator.master.id ="
          + " :masterId) and c.status = :status order by c.updatedAt desc")
  List<Collaboration> findByMasterIdAndStatus(
      @Param("masterId") UUID masterId, @Param("status") CollaborationStatus status);
}
