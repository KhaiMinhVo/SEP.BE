package com.influencermatch.backend.campaign;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface CampaignContextM3Repository extends JpaRepository<CampaignContextM3, UUID> {
    List<CampaignContextM3> findByCampaignIdOrderByContextVersionDesc(UUID campaignId);
}
