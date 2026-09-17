package com.influencermatch.backend.campaign;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface CampaignContextRepository extends JpaRepository<CampaignContext, UUID> {
    List<CampaignContext> findByCampaignIdOrderByContextVersionDesc(UUID campaignId);
}


