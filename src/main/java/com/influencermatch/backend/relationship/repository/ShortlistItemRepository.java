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
    Page<ShortlistItem> findByCampaignId(UUID campaignId, Pageable pageable);
    boolean existsByCampaignIdAndCreatorId(UUID campaignId, UUID creatorId);
    Optional<ShortlistItem> findByIdAndUserId(UUID id, UUID userId);
}
