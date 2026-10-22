package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.model.CreatorAccountVerification;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CreatorAccountVerificationRepository
    extends JpaRepository<CreatorAccountVerification, UUID>,
        JpaSpecificationExecutor<CreatorAccountVerification> {

  List<CreatorAccountVerification> findByCreatorIdAndStatus(UUID creatorId, String status);

  Optional<CreatorAccountVerification> findFirstByCreatorIdAndStatusOrderByVerifiedAtDesc(
      UUID creatorId, String status);
}
