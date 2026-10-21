package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.dto.CreatorVectorSearchProjection;
import com.influencermatch.backend.creator.model.Creator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CreatorRepository extends JpaRepository<Creator, UUID> {
  boolean existsByMasterUserId(UUID userId);

  default boolean existsByOwnerId(UUID ownerId) {
    return existsByMasterUserId(ownerId);
  }

  List<Creator> findByMasterId(UUID masterId);

  @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
  void acquireIdentityLock(@Param("lockKey") String lockKey);
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Creator c where c.id=:id")
  Optional<Creator> findLockedById(@Param("id") UUID id);
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Creator c where c.platform=:platform and c.externalId=:externalId")
  Optional<Creator> findLockedByPlatformAndExternalId(@Param("platform") String platform,@Param("externalId") String externalId);
  Optional<Creator> findByPlatformAndExternalId(String platform, String externalId);

  @Modifying
  @Query(value = "UPDATE creator SET embedding = cast(:embedding as vector) WHERE id = :id", nativeQuery = true)
  void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);

  @Query(
      value =
          "SELECT c.id AS id, "
              + "c.user_name AS userName, "
              + "c.display_name AS displayName, "
              + "c.platform AS platform, "
              + "c.avatar_url AS avatarUrl, "
              + "c.profile_url AS profileUrl, "
              + "1 - (c.embedding <=> cast(:queryVector as vector)) AS similarity "
              + "FROM creator c "
              + "WHERE c.embedding IS NOT NULL "
              + "ORDER BY c.embedding <=> cast(:queryVector as vector) ASC "
              + "LIMIT :limit",
      nativeQuery = true)
  List<CreatorVectorSearchProjection> searchSimilarCreators(
      @Param("queryVector") String queryVector, @Param("limit") int limit);
}
