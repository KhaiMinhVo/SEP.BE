package com.influencermatch.backend.campaign.repository;

import com.influencermatch.backend.campaign.controller.*;
import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.service.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignContextRepository extends JpaRepository<CampaignContext, UUID> {
  List<CampaignContext> findByCampaignIdOrderByContextVersionDesc(UUID campaignId);
}
