package com.influencermatch.backend.discovery.service;

import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.discovery.dto.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreatorDiscoveryService {
    private final CampaignRepository campaignRepository;
    private final PublicCreatorMetricRepository metricRepository;
    private final AiServiceClient aiServiceClient;

    @Transactional(readOnly = true)
    public Page<HybridDiscoveryCreatorProjection> discoverCreators(UUID campaignId, DiscoverySearchRequest req) {
        Permissions.require(Permission.SEARCH_CREATOR);
        Campaign campaign = campaignRepository.findById(campaignId)
            .orElseThrow(() -> new ResourceNotFoundException("Campaign", "id", campaignId));
        if (!campaign.getBrandProfile().getUser().getId().equals(Permissions.actor().getId()))
            throw new ForbiddenException("Campaign belongs to another brand");
        if (campaign.getStatus() == CampaignStatus.ARCHIVED)
            throw new ConflictException(ErrorCode.CAMPAIGN_CONTEXT_NOT_READY,
                "Cannot run discovery on an ARCHIVED campaign.");
        if (req == null) req = new DiscoverySearchRequest();
        validate(req);
        int size = req.getSize() == null || req.getSize() < 1 ? 50 : Math.min(req.getSize(), 50);
        int page = req.getPage() == null || req.getPage() < 0 ? 0 : req.getPage();
        Pageable pageable = PageRequest.of(page, size);

        boolean hasPlatforms = campaign.getPlatforms() != null && !campaign.getPlatforms().isEmpty();
        var niches = DiscoveryNiches.expand(campaign.getNiches());
        boolean hasNiches = !niches.isEmpty();
        // An absent niche is not permission to return the entire catalogue. Require product
        // evidence in public content instead. A name is only a last resort for incomplete drafts.
        var productNiches = DiscoveryNiches.productNiches(campaign.getProductService(), campaign.getName());
        boolean requiresProductEvidence = !hasNiches || !productNiches.isEmpty()
            || (DiscoveryNiches.onlyBroadCategories(campaign.getNiches()) && hasText(campaign.getProductService()));
        String topic = requiresProductEvidence
            ? firstText(campaign.getProductService(), campaign.getName(), campaign.getBrief()) : "";
        String productEvidenceQuery = DiscoveryNiches.productEvidenceQuery(campaign.getProductService(), campaign.getName());
        if (requiresProductEvidence && !productEvidenceQuery.isBlank()) topic = productEvidenceQuery;
        if (!hasNiches && topic.isBlank()) return Page.empty(pageable);
        String keyword = firstText(req.getKeyword(), campaign.getProductService(),
            String.join(" ", niches), campaign.getName(), campaign.getBrief());
        if (!hasText(req.getKeyword()) && !productEvidenceQuery.isBlank()) keyword = productEvidenceQuery;
        Long min = maximum(campaign.getFollowerMin(), req.getMinFollowers());
        Long max = minimum(campaign.getFollowerMax(), req.getMaxFollowers());
        if (min != null && max != null && min > max) return Page.empty(pageable);
        String queryVector = null;
        String queryText = String.join(" ",
            firstText(campaign.getProductService(), campaign.getName()),
            firstText(campaign.getBrief()),
            campaign.getNiches() == null ? "" : String.join(" ", campaign.getNiches()),
            firstText(req.getKeyword())).trim();
        if (!queryText.isBlank()) {
            try {
                var vector = aiServiceClient.getEmbedding(queryText);
                if (validEmbedding(vector)) queryVector = vector.toString();
            } catch (Exception e) {
                log.warn("Discovery query embedding unavailable; retaining context filters and lexical retrieval: {}", e.getMessage());
            }
        }
        var locations = campaign.getLocations();
        boolean hasLocations = locations != null && !locations.isEmpty();
        var pool = metricRepository.searchDiscoveryCreators(
            hasPlatforms, hasPlatforms ? campaign.getPlatforms() : List.of("__NONE__"),
            hasNiches, hasNiches ? niches : List.of("__NONE__"), min, max,
            keyword, topic, queryVector, req,
            hasLocations, hasLocations ? locations : List.of("__NONE__"),
            hasText(req.getNiche()) ? DiscoveryNiches.expand(List.of(req.getNiche())) : List.of("__NONE__"),
            productNiches.isEmpty() ? List.of("__NONE__") : productNiches,
            PageRequest.of(0, 50));
        var top = pool.getContent();
        long offset = pageable.getOffset();
        var content = offset >= top.size() ? List.<HybridDiscoveryCreatorProjection>of()
            : top.subList((int) offset, Math.min((int) offset + size, top.size()));
        return new PageImpl<>(content, pageable, top.size());
    }

    private static void validate(DiscoverySearchRequest req) {
        if ((req.getMinFollowers() != null && req.getMinFollowers() < 0)
            || (req.getMaxFollowers() != null && req.getMaxFollowers() < 0)
            || (req.getMinFollowers() != null && req.getMaxFollowers() != null
                && req.getMinFollowers() > req.getMaxFollowers()))
            throw new BadRequestException("Invalid follower range");
        if (hasText(req.getTier()) && !List.of("NANO", "MICRO", "MACRO", "MEGA")
            .contains(req.getTier().trim().toUpperCase(Locale.ROOT)))
            throw new BadRequestException("Unknown creator tier");
        if (req.getMinEr() != null && req.getMaxEr() != null
            && req.getMinEr().compareTo(req.getMaxEr()) > 0)
            throw new BadRequestException("Invalid engagement rate range");
        if (req.getMinFee() != null && req.getMaxFee() != null
            && req.getMinFee().compareTo(req.getMaxFee()) > 0)
            throw new BadRequestException("Invalid fee range");
    }

    private static Long maximum(Long a, Long b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }
    private static boolean validEmbedding(List<Float> vector) {
        return vector != null && vector.size() == 768
            && vector.stream().allMatch(v -> v != null && Float.isFinite(v))
            && vector.stream().anyMatch(v -> v != 0.0f);
    }
    private static Long minimum(Long a, Long b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String firstText(String... values) {
        for (var value : values) if (hasText(value)) return value.trim();
        return "";
    }
}
