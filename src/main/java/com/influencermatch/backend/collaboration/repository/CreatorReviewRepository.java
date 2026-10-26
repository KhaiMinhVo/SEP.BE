package com.influencermatch.backend.collaboration.repository;

import com.influencermatch.backend.collaboration.model.CreatorReview;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorReviewRepository extends JpaRepository<CreatorReview, UUID> {
  boolean existsByCollaborationId(UUID collaborationId);
  Optional<CreatorReview> findByCollaborationId(UUID collaborationId);
}
