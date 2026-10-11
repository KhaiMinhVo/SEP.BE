package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.controller.*;
import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.service.*;
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
