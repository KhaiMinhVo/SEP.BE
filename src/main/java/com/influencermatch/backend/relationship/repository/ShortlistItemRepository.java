package com.influencermatch.backend.relationship.repository;

import com.influencermatch.backend.relationship.model.ShortlistItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShortlistItemRepository extends JpaRepository<ShortlistItem, UUID> {
    Page<ShortlistItem> findByCampaignIdAndUserId(UUID campaignId, UUID userId, Pageable pageable);
    Optional<ShortlistItem> findByIdAndCampaignIdAndUserId(UUID id, UUID campaignId, UUID userId);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths={"creator","creator.master"})
    java.util.List<ShortlistItem> findByCampaignIdAndCreatorIdIn(UUID campaignId, java.util.Collection<UUID> creatorIds);
    Page<ShortlistItem> findByCampaignId(UUID campaignId, Pageable pageable);
    boolean existsByCampaignIdAndCreatorId(UUID campaignId, UUID creatorId);
    Optional<ShortlistItem> findByIdAndUserId(UUID id, UUID userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE ShortlistItem s SET s.master = :master WHERE s.creator.id = :creatorId")
    int updateMasterForCreator(@org.springframework.data.repository.query.Param("creatorId") UUID creatorId,
                               @org.springframework.data.repository.query.Param("master") com.influencermatch.backend.creator.model.CreatorMaster master);
}
