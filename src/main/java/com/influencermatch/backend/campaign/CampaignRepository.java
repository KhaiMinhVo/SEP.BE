package com.influencermatch.backend.campaign;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {
    Page<Campaign> findByBrandProfileId(UUID brandProfileId, Pageable pageable);
}


