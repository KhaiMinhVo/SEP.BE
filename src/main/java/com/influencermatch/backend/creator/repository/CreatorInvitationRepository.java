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

  Optional<CreatorInvitation> findFirstByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
      UUID creatorId,String status,java.time.LocalDateTime now);
  List<CreatorInvitation> findByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByIdAsc(UUID creatorId,String status,java.time.LocalDateTime now);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select i from CreatorInvitation i where i.id=:id")
  Optional<CreatorInvitation> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);

  Optional<CreatorInvitation> findByTokenHash(String tokenHash);

  boolean existsByTargetCreatorIdAndStatus(UUID creatorId, String status);

  List<CreatorInvitation> findByTargetCreatorId(UUID creatorId);

  List<CreatorInvitation> findByBrandIdAndStatus(UUID brandId, String status);
}
