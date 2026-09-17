package com.influencermatch.backend.auth;
import com.influencermatch.backend.auth.RefreshToken; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import java.time.LocalDateTime; import java.util.*;
public interface RefreshTokenRepository extends JpaRepository<RefreshToken,UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    @Modifying @Query("update RefreshToken t set t.revokedAt=:now where t.userId=:userId and t.revokedAt is null") int revokeAll(@Param("userId")UUID userId,@Param("now")LocalDateTime now);
}


