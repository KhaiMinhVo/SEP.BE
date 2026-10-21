package com.influencermatch.backend.collaboration.repository;

import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignOutcomeRepository extends JpaRepository<CampaignOutcome, UUID> {
  Optional<CampaignOutcome> findByCollaborationId(UUID collaborationId);

  boolean existsByCollaborationId(UUID collaborationId);
}
