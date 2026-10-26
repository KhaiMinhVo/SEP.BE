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
    Page<BrandCreatorRelationship> findByCampaignIdAndUserId(UUID campaignId, UUID userId, Pageable pageable);
    Optional<BrandCreatorRelationship> findByIdAndCampaignIdAndUserId(UUID id, UUID campaignId, UUID userId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from BrandCreatorRelationship r where r.id = :id")
    Optional<BrandCreatorRelationship> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);
    Page<BrandCreatorRelationship> findByCampaignId(UUID campaignId, Pageable pageable);
    boolean existsByCampaignIdAndCreatorId(UUID campaignId, UUID creatorId);
    Optional<BrandCreatorRelationship> findByCampaignIdAndCreatorId(UUID campaignId, UUID creatorId);
    Optional<BrandCreatorRelationship> findByIdAndUserId(UUID id, UUID userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE BrandCreatorRelationship r SET r.master = :master WHERE r.creator.id = :creatorId")
    int updateMasterForCreator(@org.springframework.data.repository.query.Param("creatorId") UUID creatorId,
                               @org.springframework.data.repository.query.Param("master") com.influencermatch.backend.creator.model.CreatorMaster master);
}
