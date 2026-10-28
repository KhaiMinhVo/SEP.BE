package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.discovery.dto.HybridDiscoveryCreatorProjection;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PublicCreatorMetricRepository
    extends JpaRepository<PublicCreatorMetric, UUID>, JpaSpecificationExecutor<PublicCreatorMetric> {

  Optional<PublicCreatorMetric> findByCreatorId(UUID creatorId);

  boolean existsByCreatorIdAndCrawlRunId(UUID creatorId, String crawlRunId);

  @Query(value="""
      SELECT DISTINCT ON (m.creator_id) m.* FROM public_creator_metric m
      WHERE m.creator_id IN (:ids) ORDER BY m.creator_id, m.collected_at DESC, m.id DESC
      """, nativeQuery=true)
  List<PublicCreatorMetric> latestForCreators(@Param("ids") java.util.Collection<UUID> ids);

  @Query(
      value = """
          SELECT * FROM (
              SELECT DISTINCT ON (COALESCE('MASTER:' || c.master_id::text, 'ACCOUNT:' || c.id::text))
                  m.id AS id,
                  c.id AS creatorId,
                  c.master_id AS masterId,
                  COALESCE(cm.canonical_name, c.display_name, c.user_name) AS displayName,
                  c.platform AS platform,
                  c.user_name AS userName,
                  c.avatar_url AS avatarUrl,
                  c.profile_url AS profileUrl,
                  m.followers AS followers,
                  m.avg_views AS avgViews,
                  m.avg_likes AS avgLikes,
                  m.niche AS niche,
                  m.category AS category,
                  m.engagement_rate AS engagementRate,
                  m.content_summary AS contentSummary,
                  CASE WHEN c.master_id IS NOT NULL THEN 'LINKED_PROFILE' ELSE 'PUBLIC_PROFILE' END AS claimStatus,
                  CASE 
                      WHEN :hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL 
                      THEN (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector)))
                      ELSE 0.0 
                  END AS semanticScore,
                  CASE 
                      WHEN :hasKeyword = true AND :formattedKeyword IS NOT NULL AND :formattedKeyword != ''
                      THEN ts_rank(m.search_vector, to_tsquery('simple', :formattedKeyword))
                      ELSE 0.0 
                  END AS ftsScore,
                  CASE 
                      WHEN :hasKeyword = true THEN (
                          (CASE WHEN :hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL THEN (0.6 * (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector)))) ELSE 0.0 END)
                        + (CASE WHEN :formattedKeyword IS NOT NULL AND :formattedKeyword != '' THEN (0.4 * ts_rank(m.search_vector, to_tsquery('simple', :formattedKeyword))) ELSE 0.0 END)
                      )
                      ELSE 0.0 
                  END AS hybridScore
              FROM public_creator_metric m
              JOIN creator c ON m.creator_id = c.id
              LEFT JOIN creator_master cm ON c.master_id = cm.id
              WHERE 
                  m.creator_type IN ('KOL', 'KOC')
                  AND (:hasPlatforms = false OR c.platform IN (:platforms))
                  AND (
                      :hasNiches = false 
                      OR m.niche IN (:niches) 
                      OR m.category IN (:niches)
                      OR EXISTS (
                          SELECT 1 FROM unnest(string_to_array(COALESCE(m.niche, ''), ',')) AS part_niche 
                          WHERE trim(part_niche) IN (:niches)
                      )
                  )
                  AND (
                      :uiNiche IS NULL 
                      OR m.niche = :uiNiche 
                      OR m.category = :uiNiche
                      OR m.niche ILIKE '%' || :uiNiche || '%'
                      OR m.category ILIKE '%' || :uiNiche || '%'
                  )
                  AND (:followerMin IS NULL OR m.followers >= :followerMin)
                  AND (:followerMax IS NULL OR m.followers <= :followerMax)
                  AND (
                      :isStrictSearch = false 
                      OR (:hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL AND (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector))) > 0.2)
                      OR (:formattedKeyword IS NOT NULL AND :formattedKeyword != '' AND m.search_vector @@ to_tsquery('simple', :formattedKeyword))
                  )
              ORDER BY 
                  COALESCE('MASTER:' || c.master_id::text, 'ACCOUNT:' || c.id::text),
                  CASE 
                      WHEN :hasKeyword = true THEN (
                          (CASE WHEN :hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL THEN (0.6 * (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector)))) ELSE 0.0 END)
                        + (CASE WHEN :formattedKeyword IS NOT NULL AND :formattedKeyword != '' THEN (0.4 * ts_rank(m.search_vector, to_tsquery('simple', :formattedKeyword))) ELSE 0.0 END)
                      )
                      ELSE 0.0 
                  END DESC,
                  m.followers DESC
          ) ranked_creators
          ORDER BY 
              ranked_creators.hybridScore DESC,
              ranked_creators.followers DESC
          """,
      countQuery = """
          SELECT count(DISTINCT COALESCE('MASTER:' || c.master_id::text, 'ACCOUNT:' || c.id::text))
          FROM public_creator_metric m
          JOIN creator c ON m.creator_id = c.id
          WHERE 
              m.creator_type IN ('KOL', 'KOC')
              AND (:hasPlatforms = false OR c.platform IN (:platforms))
              AND (
                  :hasNiches = false 
                  OR m.niche IN (:niches) 
                  OR m.category IN (:niches)
                  OR EXISTS (
                      SELECT 1 FROM unnest(string_to_array(COALESCE(m.niche, ''), ',')) AS part_niche 
                      WHERE trim(part_niche) IN (:niches)
                  )
              )
              AND (
                  :uiNiche IS NULL 
                  OR m.niche = :uiNiche 
                  OR m.category = :uiNiche
                  OR m.niche ILIKE '%' || :uiNiche || '%'
                  OR m.category ILIKE '%' || :uiNiche || '%'
              )
              AND (:followerMin IS NULL OR m.followers >= :followerMin)
              AND (:followerMax IS NULL OR m.followers <= :followerMax)
              AND (
                  :isStrictSearch = false 
                  OR (:hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL AND (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector))) > 0.2)
                  OR (:formattedKeyword IS NOT NULL AND :formattedKeyword != '' AND m.search_vector @@ to_tsquery('simple', :formattedKeyword))
              )
          """,
      nativeQuery = true
  )
    Page<HybridDiscoveryCreatorProjection> searchHybridCreators(
        @Param("hasPlatforms") boolean hasPlatforms,
        @Param("platforms") List<String> platforms,
        @Param("hasNiches") boolean hasNiches,
        @Param("niches") List<String> niches,
        @Param("uiNiche") String uiNiche,
        @Param("followerMin") Long followerMin,
        @Param("followerMax") Long followerMax,
        @Param("isStrictSearch") boolean isStrictSearch,
        @Param("hasKeyword") boolean hasKeyword,
        @Param("formattedKeyword") String formattedKeyword,
        @Param("hasVector") boolean hasVector,
        @Param("queryVector") String queryVector,
        Pageable pageable
    );

  @Query(
      value = """
          SELECT c.id AS creatorId,
                 c.master_id AS masterId,
                 c.platform AS platform,
                 c.display_name AS displayName,
                 c.user_name AS userName,
                 c.avatar_url AS avatarUrl,
                 m.id AS id,
                 m.followers AS followers,
                 m.avg_views AS avgViews,
                 m.avg_likes AS avgLikes,
                 m.engagement_rate AS engagementRate,
                 m.niche AS niche,
                 m.category AS category,
                 m.creator_type AS creatorType,
                 (1.0 - (c.embedding <=> CAST(:briefVector AS vector))) AS semanticScore
          FROM creator c
          JOIN public_creator_metric m ON c.id = m.creator_id
          WHERE (:hasExcludedIds = false OR c.id NOT IN (:excludedIds))
            AND (:hasPlatforms = false OR c.platform IN (:platforms))
            AND (:followerMin IS NULL OR m.followers >= :followerMin)
            AND (:followerMax IS NULL OR m.followers <= :followerMax)
            AND c.embedding IS NOT NULL
            AND m.id = (
                SELECT m2.id FROM public_creator_metric m2
                WHERE m2.creator_id = c.id
                ORDER BY m2.observed_at DESC NULLS LAST, m2.collected_at DESC, m2.id DESC
                LIMIT 1
            )
          ORDER BY c.embedding <=> CAST(:briefVector AS vector) ASC
          LIMIT :limit
          """,
      nativeQuery = true
  )
  List<RecommendationCandidateProjection> findTopCandidatesByEmbedding(
      @Param("briefVector") String briefVector,
      @Param("hasExcludedIds") boolean hasExcludedIds,
      @Param("excludedIds") Collection<UUID> excludedIds,
      @Param("hasPlatforms") boolean hasPlatforms,
      @Param("platforms") Collection<String> platforms,
      @Param("followerMin") Long followerMin,
      @Param("followerMax") Long followerMax,
      @Param("limit") int limit
  );

  @Query(
      value = """
          SELECT c.id AS creatorId,
                 c.master_id AS masterId,
                 c.platform AS platform,
                 c.display_name AS displayName,
                 c.user_name AS userName,
                 c.avatar_url AS avatarUrl,
                 m.id AS id,
                 m.followers AS followers,
                 m.avg_views AS avgViews,
                 m.avg_likes AS avgLikes,
                 m.engagement_rate AS engagementRate,
                 m.niche AS niche,
                 m.category AS category,
                 m.creator_type AS creatorType,
                 CASE 
                     WHEN c.embedding IS NOT NULL AND :briefVector IS NOT NULL 
                     THEN (1.0 - (c.embedding <=> CAST(:briefVector AS vector)))
                     ELSE 0.50 
                 END AS semanticScore
          FROM creator c
          JOIN shortlist_item s ON s.creator_id = c.id
          LEFT JOIN public_creator_metric m ON m.id = (
              SELECT m2.id FROM public_creator_metric m2
              WHERE m2.creator_id = c.id
              ORDER BY m2.observed_at DESC NULLS LAST, m2.collected_at DESC, m2.id DESC
              LIMIT 1
          )
          WHERE s.campaign_id = :campaignId
            AND s.status != 'REMOVED'
            AND (:hasExcludedIds = false OR c.id NOT IN (:excludedIds))
          ORDER BY semanticScore DESC
          """,
      nativeQuery = true
  )
  List<RecommendationCandidateProjection> findCandidatesByShortlist(
      @Param("campaignId") UUID campaignId,
      @Param("briefVector") String briefVector,
      @Param("hasExcludedIds") boolean hasExcludedIds,
      @Param("excludedIds") Collection<UUID> excludedIds
  );
}
