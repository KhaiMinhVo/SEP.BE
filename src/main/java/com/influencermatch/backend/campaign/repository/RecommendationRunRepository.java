package com.influencermatch.backend.campaign.repository;

import com.influencermatch.backend.campaign.model.RecommendationRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RecommendationRunRepository extends JpaRepository<RecommendationRun, UUID> {
  List<RecommendationRun> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId);
  Optional<RecommendationRun> findTopByCampaignIdOrderByCreatedAtDesc(UUID campaignId);
  List<RecommendationRun> findByBrandProfileIdOrderByCreatedAtDesc(UUID brandProfileId);
}
