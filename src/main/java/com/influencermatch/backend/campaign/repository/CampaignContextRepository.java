package com.influencermatch.backend.campaign.repository;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.campaign.service.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.campaign.controller.*;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface CampaignContextRepository extends JpaRepository<CampaignContext, UUID> {
    List<CampaignContext> findByCampaignIdOrderByContextVersionDesc(UUID campaignId);
}


