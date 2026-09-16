package com.influencermatch.backend.campaign;

import com.influencermatch.backend.brand.*;
import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.dto.PageResponse;
import com.influencermatch.backend.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CampaignService {
    private final CampaignRepository campaigns;
    private final BrandService brandService;

    @Transactional
    public CampaignResponse create(UUID brandId, CampaignRequest request, Authentication authentication) {
        BrandProfile brandProfile = brandService.requireAccessible(brandId, authentication);
        validate(request);
        Campaign campaign = Campaign.builder().brandProfile(brandProfile).name(request.name().trim())
                .productService(request.productService().trim()).objective(request.objective())
                .status(CampaignStatus.DRAFT).build();
        applyCampaign(campaign, request);
        return response(campaigns.save(campaign));
    }

    @Transactional(readOnly = true)
    public PageResponse<CampaignResponse> list(UUID brandId, Pageable pageable, Authentication authentication) {
        brandService.requireAccessible(brandId, authentication);
        return PageResponse.from(campaigns.findByBrandProfileId(brandId, pageable).map(this::loadResponse));
    }

    @Transactional(readOnly = true)
    public CampaignResponse get(UUID id, Authentication authentication) {
        Campaign campaign = requireCampaign(id);
        brandService.requireAccessible(campaign.getBrandProfile().getId(), authentication);
        return loadResponse(campaign);
    }

    @Transactional
    public CampaignResponse update(UUID id, CampaignRequest request, Authentication authentication) {
        Campaign campaign = requireCampaign(id);
        brandService.requireAccessible(campaign.getBrandProfile().getId(), authentication);
        campaign.getStatus().requireEditable();
        validate(request);
        applyCampaign(campaign, request);
        return response(campaign);
    }

    @Transactional
    public CampaignResponse archive(UUID id, Authentication authentication) {
        Campaign campaign = requireCampaign(id);
        brandService.requireAccessible(campaign.getBrandProfile().getId(), authentication);
        if (campaign.getStatus() != CampaignStatus.ARCHIVED) {
            campaign.getStatus().requireEditable();
            campaign.setStatus(CampaignStatus.ARCHIVED);
        }
        return loadResponse(campaign);
    }

    private Campaign requireCampaign(UUID id) {
        return campaigns.findById(id).orElseThrow(() -> notFound("Campaign not found: " + id));
    }
    private void validate(CampaignRequest r) {
        if (r.startDate() != null && r.endDate() != null && r.endDate().isBefore(r.startDate())) invalid("endDate must not be before startDate");
        if (greater(r.followerMin(), r.followerMax())) invalid("followerMax must be greater than or equal to followerMin");
        if (greater(r.budgetMin(), r.budgetMax())) invalid("budgetMax must be greater than or equal to budgetMin");
    }
    private boolean greater(Long min, Long max) { return min != null && max != null && min > max; }
    private boolean greater(BigDecimal min, BigDecimal max) { return min != null && max != null && min.compareTo(max) > 0; }
    private void invalid(String detail) { throw new BusinessException(ErrorCode.INVALID_CAMPAIGN_RANGE, detail); }
    private void applyCampaign(Campaign c, CampaignRequest r) {
        c.setName(r.name().trim()); c.setProductService(r.productService().trim()); c.setObjective(r.objective());
        c.setTargetAudiences(strings(r.targetAudiences())); c.setContentType(r.contentType()); c.setStartDate(r.startDate());
        c.setEndDate(r.endDate()); c.setPlatforms(r.platforms().stream().map(Enum::name).distinct().toList());
        c.setNiches(strings(r.niches())); c.setLocations(strings(r.locations())); c.setFollowerMin(r.followerMin());
        c.setFollowerMax(r.followerMax()); c.setBudgetMin(r.budgetMin()); c.setBudgetMax(r.budgetMax());
    }
    private List<String> strings(List<String> values) { return values == null ? List.of() : values.stream().map(String::trim).filter(v -> !v.isEmpty()).distinct().toList(); }
    private CampaignResponse loadResponse(Campaign c) { return response(c); }
    private CampaignResponse response(Campaign c) {
        return new CampaignResponse(c.getId(), c.getBrandProfile().getId(), c.getName(), c.getProductService(), c.getObjective(), c.getTargetAudiences(),
                c.getContentType(), c.getStartDate(), c.getEndDate(), c.getStatus(),
                c.getPlatforms().stream().map(Platform::valueOf).toList(), c.getNiches(), c.getLocations(),
                c.getFollowerMin(), c.getFollowerMax(), c.getBudgetMin(), c.getBudgetMax(), c.getCreatedAt(), c.getUpdatedAt());
    }
    private BusinessException notFound(String detail) { return new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND, detail); }
}
