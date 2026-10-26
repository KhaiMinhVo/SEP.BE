package com.influencermatch.backend.creator.meta;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MetaOAuthSessionRepository extends JpaRepository<MetaOAuthSession, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from MetaOAuthSession s where s.stateHash=:hash")
  Optional<MetaOAuthSession> lockByStateHash(@Param("hash") String hash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from MetaOAuthSession s where s.id=:id")
  Optional<MetaOAuthSession> lockById(@Param("id") UUID id);

  @Modifying
  @Query(
      "update MetaOAuthSession s set s.payloadCiphertext=null,s.status='EXPIRED' where"
          + " s.expiresAt<=:now and s.status in ('NEW','EXCHANGING','READY')")
  int expire(@Param("now") Instant now);
}
