package com.influencermatch.backend.creator.tiktok;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TikTokOAuthSessionRepository extends JpaRepository<TikTokOAuthSession, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from TikTokOAuthSession s where s.stateHash=:hash")
  Optional<TikTokOAuthSession> lockByStateHash(@Param("hash") String hash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from TikTokOAuthSession s where s.id=:id")
  Optional<TikTokOAuthSession> lockById(@Param("id") UUID id);

  @Modifying
  @Query(
      "update TikTokOAuthSession s set"
          + " s.status='EXPIRED',s.openId=null,s.username=null,s.displayName=null where"
          + " s.expiresAt<=:now and s.status in ('NEW','EXCHANGING','READY')")
  int expire(@Param("now") Instant now);
}
