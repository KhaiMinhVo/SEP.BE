package com.influencermatch.backend.campaign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.controller.*;
import com.influencermatch.backend.brand.dto.*;
import com.influencermatch.backend.brand.enums.*;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.brand.service.*;
import com.influencermatch.backend.campaign.controller.*;
import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.creator.dto.CreatorDiscoveryResponse;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricSpecification;
import com.influencermatch.backend.exception.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignService {
  private final CampaignRepository campaigns;
  private final CampaignContextRepository contexts;
  private final BrandService brandService;
  private final ObjectMapper objectMapper;
  private final PublicCreatorMetricRepository metricRepository;
  private final com.influencermatch.backend.creator.repository.PostRepository postRepository;

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CREATE_CAMPAIGN')")
  public CampaignResponse create(
      UUID brandId, CampaignRequest request, Authentication authentication) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.CREATE_CAMPAIGN);
    BrandProfile brandProfile = brandService.requireAccessible(brandId, authentication);
    validate(request);
    Campaign campaign =
        Campaign.builder()
            .brandProfile(brandProfile)
            .name(request.name().trim())
            .productService(request.productService().trim())
            .objective(request.objective())
            .status(CampaignStatus.DRAFT)
            .build();
    applyCampaign(campaign, request);
    return response(campaigns.save(campaign));
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_OWN_CAMPAIGN') or hasAuthority('VIEW_BRAND_SUPPORT_DATA')")
  public PageResponse<CampaignResponse> list(
      UUID brandId, Pageable pageable, Authentication authentication) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.VIEW_OWN_CAMPAIGN,
        com.influencermatch.backend.security.Permission.VIEW_BRAND_SUPPORT_DATA);
    brandService.requireAccessible(brandId, authentication);
    return PageResponse.from(
        campaigns.findByBrandProfileId(brandId, pageable).map(this::loadResponse));
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_OWN_CAMPAIGN') or hasAuthority('VIEW_BRAND_SUPPORT_DATA')")
  public CampaignResponse get(UUID id, Authentication authentication) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.VIEW_OWN_CAMPAIGN,
        com.influencermatch.backend.security.Permission.VIEW_BRAND_SUPPORT_DATA);
    Campaign campaign = requireCampaign(id);
    brandService.requireAccessible(campaign.getBrandProfile().getId(), authentication);
    return loadResponse(campaign);
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('SEARCH_CREATOR')")
  public PageResponse<CreatorDiscoveryResponse> discoverCreators(
      UUID campaignId, Pageable pageable, Authentication authentication) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.SEARCH_CREATOR);
    Campaign campaign = requireCampaign(campaignId);
    brandService.requireAccessible(campaign.getBrandProfile().getId(), authentication);

    int TARGET_CANDIDATES = 30;
    List<com.influencermatch.backend.creator.model.PublicCreatorMetric> finalCandidates =
        new ArrayList<>();
    Set<UUID> seenCreatorIds = new HashSet<>();
    org.springframework.data.domain.PageRequest fetchPage =
        org.springframework.data.domain.PageRequest.of(0, TARGET_CANDIDATES, pageable.getSort());

    // 1. Exact Match
    Specification<com.influencermatch.backend.creator.model.PublicCreatorMetric> spec1 =
        Specification.where(PublicCreatorMetricSpecification.isFresh())
            .and(
                PublicCreatorMetricSpecification.hasFollowersBetween(
                    campaign.getFollowerMin(), campaign.getFollowerMax()))
            .and(PublicCreatorMetricSpecification.hasPlatformIn(campaign.getPlatforms()))
            .and(PublicCreatorMetricSpecification.hasNicheIn(campaign.getNiches()))
            .and(PublicCreatorMetricSpecification.hasLocationIn(campaign.getLocations()));

    List<com.influencermatch.backend.creator.model.PublicCreatorMetric> res1 =
        metricRepository.findAll(spec1, fetchPage).getContent();
    for (com.influencermatch.backend.creator.model.PublicCreatorMetric m : res1) {
      if (seenCreatorIds.add(m.getCreator().getId())) {
        finalCandidates.add(m);
      }
    }

    // 2. Relax Location
    if (finalCandidates.size() < TARGET_CANDIDATES) {
      Specification<com.influencermatch.backend.creator.model.PublicCreatorMetric> spec2 =
          Specification.where(PublicCreatorMetricSpecification.isFresh())
              .and(
                  PublicCreatorMetricSpecification.hasFollowersBetween(
                      campaign.getFollowerMin(), campaign.getFollowerMax()))
              .and(PublicCreatorMetricSpecification.hasPlatformIn(campaign.getPlatforms()))
              .and(PublicCreatorMetricSpecification.hasNicheIn(campaign.getNiches()))
              .and(PublicCreatorMetricSpecification.notInCreatorIds(seenCreatorIds));

      List<com.influencermatch.backend.creator.model.PublicCreatorMetric> res2 =
          metricRepository.findAll(spec2, fetchPage).getContent();
      for (com.influencermatch.backend.creator.model.PublicCreatorMetric m : res2) {
        if (finalCandidates.size() >= TARGET_CANDIDATES) break;
        if (seenCreatorIds.add(m.getCreator().getId())) {
          finalCandidates.add(m);
        }
      }
    }

    // 3. Relax Follower Range
    if (finalCandidates.size() < TARGET_CANDIDATES) {
      Specification<com.influencermatch.backend.creator.model.PublicCreatorMetric> spec3 =
          Specification.where(PublicCreatorMetricSpecification.isFresh())
              .and(PublicCreatorMetricSpecification.hasPlatformIn(campaign.getPlatforms()))
              .and(PublicCreatorMetricSpecification.hasNicheIn(campaign.getNiches()))
              .and(PublicCreatorMetricSpecification.notInCreatorIds(seenCreatorIds));

      List<com.influencermatch.backend.creator.model.PublicCreatorMetric> res3 =
          metricRepository.findAll(spec3, fetchPage).getContent();
      for (com.influencermatch.backend.creator.model.PublicCreatorMetric m : res3) {
        if (finalCandidates.size() >= TARGET_CANDIDATES) break;
        if (seenCreatorIds.add(m.getCreator().getId())) {
          finalCandidates.add(m);
        }
      }
    }

    // 4. Relax Niche
    if (finalCandidates.size() < TARGET_CANDIDATES) {
      Specification<com.influencermatch.backend.creator.model.PublicCreatorMetric> spec4 =
          Specification.where(PublicCreatorMetricSpecification.isFresh())
              .and(PublicCreatorMetricSpecification.hasPlatformIn(campaign.getPlatforms()))
              .and(PublicCreatorMetricSpecification.notInCreatorIds(seenCreatorIds));

      List<com.influencermatch.backend.creator.model.PublicCreatorMetric> res4 =
          metricRepository.findAll(spec4, fetchPage).getContent();
      for (com.influencermatch.backend.creator.model.PublicCreatorMetric m : res4) {
        if (finalCandidates.size() >= TARGET_CANDIDATES) break;
        if (seenCreatorIds.add(m.getCreator().getId())) {
          finalCandidates.add(m);
        }
      }
    }

    // Fetch recent posts in batch to avoid N+1 query problem
    List<UUID> candidateCreatorIds =
        finalCandidates.stream().map(m -> m.getCreator().getId()).toList();
    List<com.influencermatch.backend.creator.model.Post> allPosts =
        candidateCreatorIds.isEmpty()
            ? new ArrayList<>()
            : postRepository.findByCreatorIdIn(candidateCreatorIds);
    Map<UUID, List<com.influencermatch.backend.creator.model.Post>> postsByCreatorId =
        allPosts.stream()
            .collect(java.util.stream.Collectors.groupingBy(p -> p.getCreator().getId()));

    // Map to responses
    List<CreatorDiscoveryResponse> responses =
        finalCandidates.stream()
            .map(
                metric ->
                    CreatorDiscoveryResponse.builder()
                        .creatorId(metric.getCreator().getId())
                        .platform(metric.getCreator().getPlatform())
                        .userName(metric.getCreator().getUserName())
                        .profileUrl(metric.getCreator().getProfileUrl())
                        .followers(metric.getFollowers())
                        .engagementRate(metric.getEngagementRate())
                        .niche(metric.getNiche())
                        .location(metric.getLocation())
                        .contentSummary(metric.getContentSummary())
                        .displayName(metric.getCreator().getDisplayName())
                        .avatarUrl(metric.getCreator().getAvatarUrl())
                        .creatorType(metric.getCreatorType())
                        .recentPosts(
                            postsByCreatorId
                                .getOrDefault(metric.getCreator().getId(), new ArrayList<>())
                                .stream()
                                .map(
                                    p ->
                                        com.influencermatch.backend.creator.dto.PostDto.builder()
                                            .platformPostId(p.getPlatformPostId())
                                            .postUrl(p.getPostUrl())
                                            .caption(p.getCaption())
                                            .views(p.getViews())
                                            .likes(p.getLikes())
                                            .comments(p.getComments())
                                            .shares(p.getShares())
                                            .postedAt(p.getPostedAt())
                                            .build())
                                .toList())
                        .build())
            .toList();

    int start = (int) pageable.getOffset();
    int end = Math.min((start + pageable.getPageSize()), responses.size());
    List<CreatorDiscoveryResponse> pagedResponses =
        (start <= end && start < responses.size())
            ? responses.subList(start, end)
            : new ArrayList<>();

    return PageResponse.from(
        new org.springframework.data.domain.PageImpl<>(pagedResponses, pageable, responses.size()));
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('UPDATE_CAMPAIGN')")
  public CampaignResponse update(UUID id, CampaignRequest request, Authentication authentication) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.UPDATE_CAMPAIGN);
    Campaign campaign = requireCampaign(id);
    brandService.requireAccessible(campaign.getBrandProfile().getId(), authentication);
    campaign.getStatus().requireEditable();
    validate(request);
    applyCampaign(campaign, request);
    return response(campaign);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('CHANGE_CAMPAIGN_STATUS')")
  public CampaignResponse archive(UUID id, Authentication authentication) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.CHANGE_CAMPAIGN_STATUS);
    Campaign campaign = requireCampaign(id);
    brandService.requireAccessible(campaign.getBrandProfile().getId(), authentication);
    if (campaign.getStatus() != CampaignStatus.ARCHIVED) {
      campaign.getStatus().requireTransitionTo(CampaignStatus.ARCHIVED);
      campaign.setStatus(CampaignStatus.ARCHIVED);
    }
    return loadResponse(campaign);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('CHANGE_CAMPAIGN_STATUS')")
  public CampaignResponse changeStatus(
      UUID id, CampaignStatusRequest request, Authentication authentication) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.CHANGE_CAMPAIGN_STATUS);
    Campaign campaign = requireCampaign(id);
    brandService.requireAccessible(campaign.getBrandProfile().getId(), authentication);
    CampaignStatus target = request.status();
    campaign.getStatus().requireTransitionTo(target);
    if (target == CampaignStatus.READY_FOR_DISCOVERY) {
      validateReady(campaign);
      createInitialContext(campaign);
    }
    campaign.setStatus(target);
    return loadResponse(campaign);
  }

  private Campaign requireCampaign(UUID id) {
    return campaigns.findById(id).orElseThrow(() -> notFound("Campaign not found: " + id));
  }

  private void validate(CampaignRequest r) {
    if (r.startDate() != null && r.endDate() != null && r.endDate().isBefore(r.startDate()))
      invalid("endDate must not be before startDate");
    if (greater(r.followerMin(), r.followerMax()))
      invalid("followerMax must be greater than or equal to followerMin");
    if (greater(r.budgetMin(), r.budgetMax()))
      invalid("budgetMax must be greater than or equal to budgetMin");
  }

  private void validateReady(Campaign campaign) {
    if (campaign.getPlatforms() == null || campaign.getPlatforms().isEmpty()) {
      throw new ConflictException(
          ErrorCode.CAMPAIGN_CONTEXT_NOT_READY,
          "At least one platform is required before discovery");
    }
  }

  private void createInitialContext(Campaign campaign) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("platforms", campaign.getPlatforms());
    value.put("niches", campaign.getNiches());
    value.put("locations", campaign.getLocations());
    value.put("targetAudiences", campaign.getTargetAudiences());
    value.put("objective", campaign.getObjective().name());
    value.put("followerMin", campaign.getFollowerMin());
    value.put("followerMax", campaign.getFollowerMax());
    value.put("budgetMin", campaign.getBudgetMin());
    value.put("budgetMax", campaign.getBudgetMax());
    value.put("contentType", campaign.getContentType());
    JsonNode contextData = objectMapper.valueToTree(value);
    contexts.save(
        CampaignContext.builder()
            .campaign(campaign)
            .contextVersion(1)
            .contextData(contextData)
            .active(true)
            .build());
  }

  private boolean greater(Long min, Long max) {
    return min != null && max != null && min > max;
  }

  private boolean greater(BigDecimal min, BigDecimal max) {
    return min != null && max != null && min.compareTo(max) > 0;
  }

  private void invalid(String detail) {
    throw new BusinessException(ErrorCode.INVALID_CAMPAIGN_RANGE, detail);
  }

  private void applyCampaign(Campaign c, CampaignRequest r) {
    c.setName(r.name().trim());
    c.setProductService(r.productService().trim());
    c.setObjective(r.objective());
    c.setTargetAudiences(strings(r.targetAudiences()));
    c.setContentType(r.contentType());
    c.setStartDate(r.startDate());
    c.setEndDate(r.endDate());
    c.setPlatforms(r.platforms().stream().map(Enum::name).distinct().toList());
    c.setNiches(strings(r.niches()));
    c.setLocations(strings(r.locations()));
    c.setFollowerMin(r.followerMin());
    c.setFollowerMax(r.followerMax());
    c.setBudgetMin(r.budgetMin());
    c.setBudgetMax(r.budgetMax());
  }

  private List<String> strings(List<String> values) {
    return values == null
        ? List.of()
        : values.stream().map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
  }

  private CampaignResponse loadResponse(Campaign c) {
    return response(c);
  }

  private CampaignResponse response(Campaign c) {
    return new CampaignResponse(
        c.getId(),
        c.getBrandProfile().getId(),
        c.getName(),
        c.getProductService(),
        c.getObjective(),
        c.getTargetAudiences(),
        c.getContentType(),
        c.getStartDate(),
        c.getEndDate(),
        c.getStatus(),
        c.getPlatforms().stream().map(Platform::valueOf).toList(),
        c.getNiches(),
        c.getLocations(),
        c.getFollowerMin(),
        c.getFollowerMax(),
        c.getBudgetMin(),
        c.getBudgetMax(),
        c.getCreatedAt(),
        c.getUpdatedAt());
  }

  private BusinessException notFound(String detail) {
    return new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND, detail);
  }
}
