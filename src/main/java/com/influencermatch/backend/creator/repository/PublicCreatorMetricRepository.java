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

  // Creator discovery uses observed AI/crawl labels, not the crawl configuration.
  @Query(value = """
      SELECT DISTINCT BTRIM(part) AS name
      FROM public_creator_metric metric
      CROSS JOIN LATERAL unnest(string_to_array(metric.niche, ',')) AS part
      WHERE BTRIM(part) <> '' AND UPPER(BTRIM(part)) NOT IN ('UNKNOWN', 'GENERAL')
      ORDER BY name
      """, nativeQuery = true)
  List<String> findDistinctNicheNames();

  Optional<PublicCreatorMetric> findByCreatorId(UUID creatorId);

  boolean existsByCreatorIdAndCrawlRunId(UUID creatorId, String crawlRunId);

  @Query(value="""
      SELECT DISTINCT ON (m.creator_id) m.* FROM public_creator_metric m
      WHERE m.creator_id IN (:ids) ORDER BY m.creator_id, m.collected_at DESC, m.id DESC
      """, nativeQuery=true)
  List<PublicCreatorMetric> latestForCreators(@Param("ids") java.util.Collection<UUID> ids);

  // Both endpoints use the same eligibility, latest snapshot, ranking and master grouping.
  String DISCOVERY_CTES = """
      WITH eligible AS (
          SELECT m.id, c.id AS creatorId, c.master_id AS masterId,
                 COALESCE(cm.canonical_name, c.display_name, c.user_name) AS displayName,
                 c.platform, c.user_name AS userName, c.avatar_url AS avatarUrl,
                 c.profile_url AS profileUrl, m.followers, m.avg_views AS avgViews,
                 m.avg_likes AS avgLikes, m.niche, m.category,
                 m.engagement_rate AS engagementRate, m.content_summary AS contentSummary,
                 CASE WHEN c.master_id IS NOT NULL THEN 'LINKED_PROFILE' ELSE 'PUBLIC_PROFILE' END AS claimStatus,
                 COALESCE('MASTER:' || c.master_id::text, 'ACCOUNT:' || c.id::text) AS groupKey,
                 ts_rank(m.search_vector || post_evidence.search_vector || to_tsvector('simple', COALESCE(c.display_name, '') || ' ' || c.user_name),
                     websearch_to_tsquery('simple', :keyword), 32) AS ftsScore,
                 m.actual_fee AS actualFee,
                 COALESCE(m.actual_fee, (
                   COALESCE(m.estimated_fee_min, CASE
                     WHEN COALESCE(m.followers, 0) < 10000 THEN 200000 WHEN COALESCE(m.followers, 0) < 50000 THEN 1000000
                     WHEN COALESCE(m.followers, 0) < 100000 THEN 2500000 WHEN COALESCE(m.followers, 0) < 500000 THEN 5000000
                     WHEN COALESCE(m.followers, 0) < 1000000 THEN 15000000 ELSE 30000000 END)
                   + COALESCE(m.estimated_fee_max, CASE
                     WHEN COALESCE(m.followers, 0) < 10000 THEN 500000 WHEN COALESCE(m.followers, 0) < 50000 THEN 2500000
                     WHEN COALESCE(m.followers, 0) < 100000 THEN 5000000 WHEN COALESCE(m.followers, 0) < 500000 THEN 15000000
                     WHEN COALESCE(m.followers, 0) < 1000000 THEN 30000000 ELSE 60000000 END)) / 2.0) AS comparableFee
          FROM public_creator_metric m
          JOIN creator c ON m.creator_id = c.id
          LEFT JOIN creator_master cm ON c.master_id = cm.id
          LEFT JOIN LATERAL (
              SELECT to_tsvector('simple', COALESCE(string_agg(p.caption, ' '), '')) AS search_vector
              FROM post p WHERE p.creator_id = c.id
          ) post_evidence ON true
          WHERE m.creator_type IN ('KOL', 'KOC') AND m.freshness_status = 'FRESH'
            AND m.id = (SELECT latest.id FROM public_creator_metric latest
                        WHERE latest.creator_id = c.id
                        ORDER BY latest.collected_at DESC, latest.id DESC LIMIT 1)
            AND (:hasPlatforms = false OR c.platform IN (:platforms))
            AND (COALESCE(BTRIM(CAST(:#{#filters.platform} AS text)), '') = ''
                 OR UPPER(c.platform) = UPPER(BTRIM(CAST(:#{#filters.platform} AS text))))
            AND (:hasLocations = false OR c.location IN (:locations))
            AND (:hasNiches = false OR LOWER(BTRIM(m.category)) IN (:niches)
                 OR EXISTS (SELECT 1 FROM unnest(string_to_array(COALESCE(m.niche, ''), ',')) AS part
                            WHERE LOWER(BTRIM(part)) IN (:niches)))
            AND (COALESCE(BTRIM(CAST(:#{#filters.niche} AS text)), '') = ''
                 OR LOWER(BTRIM(m.category)) IN (:uiNiches)
                 OR EXISTS (SELECT 1 FROM unnest(string_to_array(COALESCE(m.niche, ''), ',')) AS part
                            WHERE LOWER(BTRIM(part)) IN (:uiNiches)))
            AND (CAST(:followerMin AS bigint) IS NULL OR m.followers >= :followerMin)
            AND (CAST(:followerMax AS bigint) IS NULL OR m.followers <= :followerMax)
            AND (COALESCE(BTRIM(CAST(:#{#filters.tier} AS text)), '') = ''
                 OR CASE UPPER(BTRIM(CAST(:#{#filters.tier} AS text)))
                     WHEN 'NANO' THEN m.followers < 10000
                     WHEN 'MICRO' THEN m.followers BETWEEN 10000 AND 99999
                     WHEN 'MACRO' THEN m.followers BETWEEN 100000 AND 999999
                     WHEN 'MEGA' THEN m.followers >= 1000000 ELSE false END)
            AND (CAST(:#{#filters.minEr} AS numeric) IS NULL OR m.engagement_rate >= :#{#filters.minEr})
            AND (CAST(:#{#filters.maxEr} AS numeric) IS NULL OR m.engagement_rate <= :#{#filters.maxEr})
            AND (CAST(:#{#filters.isClaimed} AS boolean) IS NULL
                 OR :#{#filters.isClaimed} = (c.source_type = 'AUTHORIZED' OR c.authorized_at IS NOT NULL))
            AND (:topicQuery = '' OR (m.search_vector || post_evidence.search_vector) @@ websearch_to_tsquery('simple', :topicQuery)
                 OR LOWER(BTRIM(m.category)) IN (:topicNiches)
                 OR EXISTS (SELECT 1 FROM unnest(string_to_array(COALESCE(m.niche, ''), ',')) AS topic_part
                            WHERE LOWER(BTRIM(topic_part)) IN (:topicNiches)))
            AND (COALESCE(BTRIM(CAST(:#{#filters.keyword} AS text)), '') = ''
                 OR (m.search_vector || post_evidence.search_vector || to_tsvector('simple', COALESCE(c.display_name, '') || ' ' || c.user_name))
                     @@ websearch_to_tsquery('simple', CAST(:#{#filters.keyword} AS text)))
      ), filtered AS MATERIALIZED (
          SELECT * FROM eligible
          WHERE (CAST(:#{#filters.minFee} AS numeric) IS NULL OR comparableFee >= :#{#filters.minFee})
            AND (CAST(:#{#filters.maxFee} AS numeric) IS NULL OR comparableFee <= :#{#filters.maxFee})
      ), retrieved AS (
          SELECT f.*,
                 CASE WHEN CAST(:queryVector AS text) IS NOT NULL AND c.embedding IS NOT NULL
                           AND vector_norm(c.embedding) > 0
                      THEN 1.0 - (c.embedding <=> CAST(CAST(:queryVector AS text) AS vector))
                      ELSE NULL END AS semanticScore,
                 CASE WHEN CAST(:queryVector AS text) IS NULL THEN 'LEXICAL' ELSE 'VECTOR_HYBRID' END AS retrievalMethod
          FROM filtered f JOIN creator c ON c.id = f.creatorId
      ), ranked AS (
          SELECT *, CASE WHEN CAST(:queryVector AS text) IS NULL THEN ftsScore
                         ELSE 0.6 * COALESCE(GREATEST(0.0, semanticScore), 0.0) + 0.4 * ftsScore END AS hybridScore
          FROM retrieved
      ), grouped AS (
          SELECT DISTINCT ON (groupKey) * FROM ranked
          ORDER BY groupKey, hybridScore DESC, followers DESC NULLS LAST, creatorId
      )
      """;

  @Query(value = DISCOVERY_CTES + """
      SELECT * FROM grouped
      ORDER BY
        CASE WHEN UPPER(COALESCE(CAST(:#{#filters.sortBy} AS text), '')) = 'LOWEST_COMPARABLE_FEE'
             THEN CASE WHEN actualFee IS NULL THEN 1 ELSE 0 END END,
        CASE WHEN UPPER(COALESCE(CAST(:#{#filters.sortBy} AS text), '')) = 'LOWEST_COMPARABLE_FEE'
             THEN comparableFee END,
        hybridScore DESC, followers DESC NULLS LAST, creatorId
      """,
      countQuery = DISCOVERY_CTES + "SELECT count(DISTINCT groupKey) FROM filtered", nativeQuery = true)
  Page<HybridDiscoveryCreatorProjection> searchDiscoveryCreators(
      @Param("hasPlatforms") boolean hasPlatforms, @Param("platforms") List<String> platforms,
      @Param("hasNiches") boolean hasNiches, @Param("niches") List<String> niches,
      @Param("followerMin") Long followerMin, @Param("followerMax") Long followerMax,
      @Param("keyword") String keyword, @Param("topicQuery") String topicQuery,
      @Param("queryVector") String queryVector,
      @Param("filters") com.influencermatch.backend.discovery.dto.DiscoverySearchRequest filters,
      @Param("hasLocations") boolean hasLocations, @Param("locations") List<String> locations,
      @Param("uiNiches") List<String> uiNiches,
      @Param("topicNiches") List<String> topicNiches,
      Pageable pageable);

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
                     WHEN c.embedding IS NOT NULL AND :briefVector IS NOT NULL AND vector_norm(c.embedding) > 0
                     THEN (1.0 - (c.embedding <=> CAST(:briefVector AS vector)))
                     ELSE NULL
                 END AS semanticScore
          FROM creator c
          JOIN shortlist_item s ON s.creator_id = c.id
          LEFT JOIN public_creator_metric m ON m.id = (
              SELECT m2.id FROM public_creator_metric m2
              WHERE m2.creator_id = c.id
              ORDER BY m2.collected_at DESC, m2.id DESC
              LIMIT 1
          )
          WHERE s.campaign_id = :campaignId
            AND s.status != 'REMOVED'
            AND (m.id IS NULL OR (m.creator_type IN ('KOL', 'KOC') AND m.freshness_status = 'FRESH'))
            AND (:hasExcludedIds = false OR c.id NOT IN (:excludedIds))
          ORDER BY semanticScore DESC NULLS LAST, c.id
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
