package com.influencermatch.backend.discovery.service;

import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.discovery.dto.DiscoveryContext;
import com.influencermatch.backend.discovery.dto.DiscoverySearchRequest;
import com.influencermatch.backend.discovery.dto.HybridDiscoveryCreatorProjection;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreatorDiscoveryService {

    private final CampaignRepository campaignRepository;
    private final PublicCreatorMetricRepository metricRepository;
    private final AiServiceClient aiServiceClient;

    @Transactional(readOnly = true)
    public Page<HybridDiscoveryCreatorProjection> discoverCreators(UUID campaignId, DiscoverySearchRequest req) {
        // 1. Fetch Campaign and validate existence
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign", "id", campaignId));

        // Allow DRAFT, READY_FOR_DISCOVERY, and ACTIVE campaigns to preview/discover creators.
        // Only block ARCHIVED campaigns.
        if (campaign.getStatus() == CampaignStatus.ARCHIVED) {
            throw new ConflictException(ErrorCode.CAMPAIGN_CONTEXT_NOT_READY, "Cannot run discovery on an ARCHIVED campaign.");
        }

        // BƯỚC 1: LỌC KHUNG CỨNG (Extract M3 Context)
        DiscoveryContext ctx = DiscoveryContext.builder()
                .platforms(campaign.getPlatforms())
                .niches(campaign.getNiches())
                .followerMin(campaign.getFollowerMin())
                .followerMax(campaign.getFollowerMax())
                .budgetMin(campaign.getBudgetMin())
                .budgetMax(campaign.getBudgetMax())
                .build();

        // BƯỚC 2: TÌM KIẾM THÔNG MINH KÉP (AI Semantic + FTS Lexical)
        boolean hasKeyword = (req.getKeyword() != null && !req.getKeyword().trim().isEmpty());
        String formattedKeyword = formatKeyword(req.getKeyword());
        
        List<Float> vector = Collections.emptyList();
        boolean hasVector = false;
        String queryVectorStr = null;

        if (hasKeyword) {
            try {
                // Nhánh 1: Gọi Python AI Service lấy vector 768 chiều
                vector = aiServiceClient.getEmbedding(req.getKeyword().trim());
                if (vector != null && !vector.isEmpty()) {
                    hasVector = true;
                    queryVectorStr = vector.toString();
                }
            } catch (Exception e) {
                // Phao cứu sinh (Circuit Breaker): AI Service lỗi -> Tự trượt về FTS
                log.warn("AI Service unavailable or timed out. Falling back to 100% Lexical FTS: {}", e.getMessage());
                hasVector = false;
                queryVectorStr = null;
            }
        }

        Pageable pageable = PageRequest.of(req.getPage(), req.getSize());

        // BƯỚC 3: TRỘN ĐIỂM XẾP HẠNG (Chạy Native Hybrid Query)
        Page<HybridDiscoveryCreatorProjection> pool = executeHybridSearch(ctx, req, hasKeyword, formattedKeyword, hasVector, queryVectorStr, pageable);

        // BƯỚC 4: TỰ CHỮA LÀNH (FALLBACK - Nới lỏng ràng buộc nếu < 5 ứng viên)
        if (req.getPage() == 0 && pool.getContent().size() < 5) {
            int attempt = 1;
            while (pool.getContent().size() < 5 && attempt <= 2) {
                log.info("Discovery Fallback triggered: Attempt {}", attempt);
                relaxConstraints(ctx, req, attempt);
                pool = executeHybridSearch(ctx, req, hasKeyword, formattedKeyword, hasVector, queryVectorStr, pageable);
                attempt++;
            }
        }

        return pool;
    }

    private Page<HybridDiscoveryCreatorProjection> executeHybridSearch(
            DiscoveryContext ctx,
            DiscoverySearchRequest req,
            boolean hasKeyword,
            String formattedKeyword,
            boolean hasVector,
            String queryVectorStr,
            Pageable pageable) {

        boolean hasPlatforms = (ctx.getPlatforms() != null && !ctx.getPlatforms().isEmpty());
        List<String> safePlatforms = hasPlatforms ? ctx.getPlatforms() : List.of("__NONE__");

        boolean hasNiches = (ctx.getNiches() != null && !ctx.getNiches().isEmpty());
        List<String> safeNiches = hasNiches ? ctx.getNiches() : List.of("__NONE__");

        Long followerMin = req.getMinFollowers() != null ? req.getMinFollowers() : ctx.getFollowerMin();
        Long followerMax = req.getMaxFollowers() != null ? req.getMaxFollowers() : ctx.getFollowerMax();

        return metricRepository.searchHybridCreators(
                hasPlatforms,
                safePlatforms,
                hasNiches,
                safeNiches,
                req.getNiche(),
                followerMin,
                followerMax,
                hasKeyword,
                formattedKeyword,
                hasVector,
                queryVectorStr,
                pageable
        );
    }

    private void relaxConstraints(DiscoveryContext ctx, DiscoverySearchRequest req, int level) {
        if (level == 1) {
            // Lần 1: Nới biên độ follower ra 20%
            if (ctx.getFollowerMin() != null) {
                ctx.setFollowerMin((long) (ctx.getFollowerMin() * 0.8));
            }
            if (ctx.getFollowerMax() != null) {
                ctx.setFollowerMax((long) (ctx.getFollowerMax() * 1.2));
            }
            if (req.getMinFollowers() != null) {
                req.setMinFollowers((long) (req.getMinFollowers() * 0.8));
            }
            if (req.getMaxFollowers() != null) {
                req.setMaxFollowers((long) (req.getMaxFollowers() * 1.2));
            }
        } else if (level == 2) {
            // Lần 2: Gỡ bỏ giới hạn Niche
            ctx.setNiches(null);
            req.setNiche(null);
        }
    }

    private String formatKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return "";
        }
        String cleaned = keyword.replaceAll("[&|!*:()<>]", " ").trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        String[] terms = cleaned.split("\\s+");
        return String.join(" | ", terms);
    }
}