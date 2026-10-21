package com.influencermatch.backend.campaign.repository;

import com.influencermatch.backend.campaign.controller.*;
import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.service.*;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
  @org.springframework.data.jpa.repository.Query("select c from Campaign c where c.id = :id")
  java.util.Optional<Campaign> findLockedForCollaborationById(@org.springframework.data.repository.query.Param("id") UUID id);
  Page<Campaign> findByBrandProfileId(UUID brandProfileId, Pageable pageable);
}
