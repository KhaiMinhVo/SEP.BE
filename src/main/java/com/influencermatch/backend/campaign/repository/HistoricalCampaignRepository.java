package com.influencermatch.backend.campaign.repository;

import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HistoricalCampaignRepository extends JpaRepository<HistoricalCampaign, UUID> {
  List<HistoricalCampaign> findByCreatorId(UUID creatorId);
  List<HistoricalCampaign> findByMasterId(UUID masterId);
  List<HistoricalCampaign> findByCreatorIdIn(Collection<UUID> creatorIds);
  List<HistoricalCampaign> findByMasterIdIn(Collection<UUID> masterIds);
}
