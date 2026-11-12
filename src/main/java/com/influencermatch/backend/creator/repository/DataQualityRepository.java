package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface DataQualityRepository extends JpaRepository<PublicCreatorMetric, UUID> {
  String PENDING = """
      FROM public_creator_metric m
      WHERE m.creator_type='KOC' AND m.data_confidence < 0.7 AND m.quality_reviewed_at IS NULL
      AND NOT EXISTS (
        SELECT 1 FROM public_creator_metric newer WHERE newer.creator_id=m.creator_id
        AND (newer.collected_at > m.collected_at OR
             (newer.collected_at=m.collected_at AND newer.id > m.id)))
      """;

  @Query(value = "SELECT m.* " + PENDING + " ORDER BY m.data_confidence, m.collected_at, m.id",
      countQuery = "SELECT count(*) " + PENDING, nativeQuery = true)
  Page<PublicCreatorMetric> pending(Pageable pageable);

  @Query("select m.creator.id from PublicCreatorMetric m where m.id=:id")
  Optional<UUID> creatorId(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from PublicCreatorMetric m where m.id=:id")
  Optional<PublicCreatorMetric> findLocked(@Param("id") UUID id);

  Optional<PublicCreatorMetric> findFirstByCreatorIdOrderByCollectedAtDescIdDesc(UUID creatorId);
}
