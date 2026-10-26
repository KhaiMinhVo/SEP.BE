package com.influencermatch.backend.collaboration.repository;

import com.influencermatch.backend.collaboration.model.EvidenceAsset;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EvidenceAssetRepository extends JpaRepository<EvidenceAsset, UUID> {
  List<EvidenceAsset> findByCollaborationIdOrderByUploadedAtDesc(UUID collaborationId);

  List<EvidenceAsset> findByOutcomeIdOrderByUploadedAtDesc(UUID outcomeId);
}
