package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.discovery.dto.HybridDiscoveryCreatorProjection;
import java.util.List;
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

    @Query(value="""
        SELECT DISTINCT ON (m.creator_id) m.* FROM public_creator_metric m
        WHERE m.creator_id IN (:ids) ORDER BY m.creator_id, m.collected_at DESC, m.id DESC
        """, nativeQuery=true)
    List<PublicCreatorMetric> latestForCreators(@Param("ids") java.util.Collection<UUID> ids);

    @Query(
        value = """
            SELECT 
                m.id AS id,
                c.id AS creatorId,
                c.user_name AS userName,
                c.display_name AS displayName,
                c.platform AS platform,
                c.avatar_url AS avatarUrl,
                c.profile_url AS profileUrl,
                m.followers AS followers,
                m.avg_views AS avgViews,
                m.avg_likes AS avgLikes,
                m.niche AS niche,
                m.category AS category,
                m.engagement_rate AS engagementRate,
                m.content_summary AS contentSummary,
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
            WHERE 
                (:hasPlatforms = false OR c.platform IN (:platforms))
                AND (:hasNiches = false OR m.niche IN (:niches))
                AND (:uiNiche IS NULL OR m.niche = :uiNiche)
                AND (:followerMin IS NULL OR m.followers >= :followerMin)
                AND (:followerMax IS NULL OR m.followers <= :followerMax)
                AND (
                    :hasKeyword = false 
                    OR (:hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL AND (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector))) > 0.3)
                    OR (:formattedKeyword IS NOT NULL AND :formattedKeyword != '' AND m.search_vector @@ to_tsquery('simple', :formattedKeyword))
                )
            ORDER BY 
                CASE 
                    WHEN :hasKeyword = true THEN (
                        (CASE WHEN :hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL THEN (0.6 * (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector)))) ELSE 0.0 END)
                      + (CASE WHEN :formattedKeyword IS NOT NULL AND :formattedKeyword != '' THEN (0.4 * ts_rank(m.search_vector, to_tsquery('simple', :formattedKeyword))) ELSE 0.0 END)
                    )
                    ELSE 0.0 
                END DESC,
                m.followers DESC
            """,
        countQuery = """
            SELECT count(*)
            FROM public_creator_metric m
            JOIN creator c ON m.creator_id = c.id
            WHERE 
                (:hasPlatforms = false OR c.platform IN (:platforms))
                AND (:hasNiches = false OR m.niche IN (:niches))
                AND (:uiNiche IS NULL OR m.niche = :uiNiche)
                AND (:followerMin IS NULL OR m.followers >= :followerMin)
                AND (:followerMax IS NULL OR m.followers <= :followerMax)
                AND (
                    :hasKeyword = false 
                    OR (:hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL AND (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector))) > 0.3)
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
        @Param("hasKeyword") boolean hasKeyword,
        @Param("formattedKeyword") String formattedKeyword,
        @Param("hasVector") boolean hasVector,
        @Param("queryVector") String queryVector,
        Pageable pageable
    );
}
