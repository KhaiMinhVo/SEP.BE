package com.influencermatch.backend.creator.email;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ClaimEmailJobRepository extends JpaRepository<ClaimEmailJob,UUID> {
  Optional<ClaimEmailJob> findByInvitationId(UUID invitationId);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from ClaimEmailJob j where j.id=:id")
  Optional<ClaimEmailJob> lockById(@Param("id") UUID id);
  @Query("select j.id from ClaimEmailJob j where (j.status='QUEUED' and j.nextAttemptAt<=:now) or " +
      "(j.status='PROCESSING' and j.leaseUntil<=:now) or " +
      "(j.payloadCiphertext is not null and j.expiresAt<=:now) order by j.createdAt,j.id")
  List<UUID> findDue(@Param("now") LocalDateTime now,Pageable page);
}
