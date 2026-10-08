package com.influencermatch.backend.discovery.service;

import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.discovery.dto.DiscoveryContext;
import com.influencermatch.backend.discovery.dto.DiscoverySearchRequest;
import com.influencermatch.backend.discovery.repository.CreatorDiscoverySpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreatorDiscoveryService {

    private final CampaignRepository campaignRepository;
    private final PublicCreatorMetricRepository metricRepository;

    @Transactional(readOnly = true)
    public Page<PublicCreatorMetric> discoverCreators(UUID campaignId, DiscoverySearchRequest req) {
        // 1. Fetch Campaign and validate status
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found with ID: " + campaignId));

        if (campaign.getStatus() == CampaignStatus.DRAFT) {
            throw new IllegalStateException("Cannot run discovery on a DRAFT campaign. Please activate or ready it first.");
        }

        // 2. Map Campaign M3 requirements to DiscoveryContext
        DiscoveryContext ctx = DiscoveryContext.builder()
                .platforms(campaign.getPlatforms())
                .niches(campaign.getNiches())
                .followerMin(campaign.getFollowerMin())
                .followerMax(campaign.getFollowerMax())
                .budgetMin(campaign.getBudgetMin())
                .budgetMax(campaign.getBudgetMax())
                .build();

        // 3. Initialize Pageable and fetch initial pool
        Pageable pageable = PageRequest.of(req.getPage(), req.getSize());
        Page<PublicCreatorMetric> pool = metricRepository.findAll(
                CreatorDiscoverySpecification.buildHybridSpec(ctx, req), pageable
        );

        // 4. Fallback Logic (Constraint Relaxation)
        // Only trigger fallback if we are on the first page and results are less than 5
        if (req.getPage() == 0 && pool.getContent().size() < 5) {
            int attempt = 1;
            while (pool.getContent().size() < 5 && attempt <= 2) {
                log.info("Discovery Fallback triggered: Attempt {}", attempt);
                
                // Relax constraints incrementally
                relaxConstraints(ctx, req, attempt);
                
                // Re-run the query with relaxed constraints
                pool = metricRepository.findAll(
                        CreatorDiscoverySpecification.buildHybridSpec(ctx, req), pageable
                );
                
                attempt++;
            }
        }

        return pool;
    }

    private void relaxConstraints(DiscoveryContext ctx, DiscoverySearchRequest req, int level) {
        if (level == 1) {
            // Lan 1: Noi bien do Follower ra 20% (Ca o M3 lan UI Request neu co)
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
            // Lan 2: Go bo gioi han Niche de lay tep rong nhat co the
            ctx.setNiches(null);
            req.setNiche(null);
        }
    }
}
