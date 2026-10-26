package com.influencermatch.backend.relationship.repository;

import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ShortlistItemRepository
    extends JpaRepository<ShortlistItem, UUID>, JpaSpecificationExecutor<ShortlistItem> {

  Page<ShortlistItem> findByCampaignIdAndUserId(UUID campaignId, UUID userId, Pageable pageable);

  Optional<ShortlistItem> findByIdAndCampaignIdAndUserId(UUID id, UUID campaignId, UUID userId);

  Optional<ShortlistItem> findByIdAndCampaignId(UUID id, UUID campaignId);

  @EntityGraph(attributePaths = {"creator", "creator.master"})
  List<ShortlistItem> findByCampaignIdAndCreatorIdIn(UUID campaignId, Collection<UUID> creatorIds);

  @EntityGraph(attributePaths = {"creator", "creator.master"})
  List<ShortlistItem> findByCampaignIdAndIdIn(UUID campaignId, Collection<UUID> ids);

  Page<ShortlistItem> findByCampaignId(UUID campaignId, Pageable pageable);

  boolean existsByCampaignIdAndCreatorId(UUID campaignId, UUID creatorId);

  Optional<ShortlistItem> findByCampaignIdAndCreatorId(UUID campaignId, UUID creatorId);

  List<ShortlistItem> findByCampaignIdAndStatus(UUID campaignId, ShortlistStatus status);

  long countByCampaignId(UUID campaignId);

  long countByCampaignIdAndStatus(UUID campaignId, ShortlistStatus status);

  long countByCampaignIdAndStatusNot(UUID campaignId, ShortlistStatus status);

  Optional<ShortlistItem> findByIdAndUserId(UUID id, UUID userId);

  @Modifying
  @Query("UPDATE ShortlistItem s SET s.master = :master WHERE s.creator.id = :creatorId")
  int updateMasterForCreator(
      @Param("creatorId") UUID creatorId,
      @Param("master") com.influencermatch.backend.creator.model.CreatorMaster master);
}
