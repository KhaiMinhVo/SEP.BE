package com.influencermatch.backend.relationship.repository;

import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrandCreatorRelationshipRepository extends JpaRepository<BrandCreatorRelationship, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from BrandCreatorRelationship r where r.id = :id")
    Optional<BrandCreatorRelationship> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);
    Page<BrandCreatorRelationship> findByCampaignId(UUID campaignId, Pageable pageable);
    boolean existsByCampaignIdAndCreatorId(UUID campaignId, UUID creatorId);
    Optional<BrandCreatorRelationship> findByIdAndUserId(UUID id, UUID userId);
}
