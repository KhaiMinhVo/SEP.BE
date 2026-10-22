package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.model.CreatorInvitation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CreatorInvitationRepository
    extends JpaRepository<CreatorInvitation, UUID>, JpaSpecificationExecutor<CreatorInvitation> {

  Optional<CreatorInvitation> findByTokenHash(String tokenHash);

  boolean existsByTargetCreatorIdAndStatus(UUID creatorId, String status);

  List<CreatorInvitation> findByTargetCreatorId(UUID creatorId);

  List<CreatorInvitation> findByBrandIdAndStatus(UUID brandId, String status);
}
