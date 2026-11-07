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
import com.influencermatch.backend.creator.dto.CreatorDiscoveryFilterRequest;
import com.influencermatch.backend.creator.dto.CreatorDiscoveryResponse;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.enums.FollowerFeeTier;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricSpecification;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
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
  private final com.influencermatch.backend.billing.service.SubscriptionService subscriptionService;
  private final ObjectMapper objectMapper;
  private final PublicCreatorMetricRepository metricRepository;
  private final com.influencermatch.backend.creator.repository.PostRepository postRepository;

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CREATE_CAMPAIGN')")
  public CampaignResponse create(
      UUID brandId, CampaignRequest request, Authentication authentication) {
    Permissions.require(
        Permission.CREATE_CAMPAIGN);
    BrandProfile brandProfile = brandService.requireAccessible(brandId, authentication);
    validate(request);
    subscriptionService.checkAndDeductCampaignQuota(brandProfile.getId());
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
    Permissions.require(
        Permission.VIEW_OWN_CAMPAIGN,
        Permission.VIEW_BRAND_SUPPORT_DATA);
    brandService.requireAccessible(brandId, authentication);
    return PageResponse.from(
        campaigns.findByBrandProfileId(brandId, pageable).map(this::loadResponse));
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_OWN_CAMPAIGN') or hasAuthority('VIEW_BRAND_SUPPORT_DATA')")
  public CampaignResponse get(UUID id, Authentication authentication) {
    Permissions.require(
        Permission.VIEW_OWN_CAMPAIGN,
        Permission.VIEW_BRAND_SUPPORT_DATA);
    Campaign campaign = requireCampaign(id);
    brandService.requireAccessible(campaign.getBrandProfile().getId(), authentication);
    return loadResponse(campaign);
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('SEARCH_CREATOR')")
  public PageResponse<CreatorDiscoveryResponse> discoverCreators(
      UUID campaignId, Pageable pageable, Authentication authentication) {
    return discoverCreators(campaignId, new CreatorDiscoveryFilterRequest(), pageable, authentication);
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('SEARCH_CREATOR')")
  public PageResponse<CreatorDiscoveryResponse> discoverCreators(
      UUID campaignId,
      CreatorDiscoveryFilterRequest filter,
      Pageable pageable,
      Authentication authentication) {
    Permissions.require(Permission.SEARCH_CREATOR);
    Campaign campaign = requireCampaign(campaignId);
    brandService.requireAccessible(campaign.getBrandProfile().getId(), authentication);

    if (filter == null) {
      filter = new CreatorDiscoveryFilterRequest();
    }

    int TARGET_CANDIDATES = 50;
    List<com.influencermatch.backend.creator.model.PublicCreatorMetric> finalCandidates =
        new ArrayList<>();
    Set<UUID> fallbackCandidateIds = new HashSet<>();
    Set<UUID> seenCreatorIds = new HashSet<>();
    org.springframework.data.domain.PageRequest fetchPage =
        org.springframework.data.domain.PageRequest.of(0, TARGET_CANDIDATES);

    // 1. HARD CONDITIONS: Platform & Status FRESH
    Specification<com.influencermatch.backend.creator.model.PublicCreatorMetric> baseHardSpec =
        Specification.where(PublicCreatorMetricSpecification.isFresh());
    if (filter.getPlatform() != null && !filter.getPlatform().isBlank()) {
      baseHardSpec = baseHardSpec.and(PublicCreatorMetricSpecification.hasPlatform(filter.getPlatform()));
    } else if (campaign.getPlatforms() != null && !campaign.getPlatforms().isEmpty()) {
      baseHardSpec = baseHardSpec.and(PublicCreatorMetricSpecification.hasPlatformIn(campaign.getPlatforms()));
    }

    // 2. SOFT / FLEXIBLE FILTERS (Manual Filter & Brief Criteria)
    Specification<com.influencermatch.backend.creator.model.PublicCreatorMetric> exactSpec = baseHardSpec;

    // Follower / Tier filter
    if (filter.getTier() != null && !filter.getTier().isBlank()) {
      exactSpec = exactSpec.and(PublicCreatorMetricSpecification.hasFollowerTier(filter.getTier()));
    } else {
      exactSpec = exactSpec.and(
          PublicCreatorMetricSpecification.hasFollowersBetween(
              campaign.getFollowerMin(), campaign.getFollowerMax()));
    }

    // Niche filter
    if (filter.getNiche() != null && !filter.getNiche().isBlank()) {
      exactSpec = exactSpec.and(PublicCreatorMetricSpecification.hasNiche(filter.getNiche()));
    } else if (campaign.getNiches() != null && !campaign.getNiches().isEmpty()) {
      exactSpec = exactSpec.and(PublicCreatorMetricSpecification.hasNicheIn(campaign.getNiches()));
    }

    // Location (from campaign)
    if (campaign.getLocations() != null && !campaign.getLocations().isEmpty()) {
      exactSpec = exactSpec.and(PublicCreatorMetricSpecification.hasLocationIn(campaign.getLocations()));
    }

    // ER filter (minEr, maxEr)
    if (filter.getMinEr() != null || filter.getMaxEr() != null) {
      exactSpec = exactSpec.and(
          PublicCreatorMetricSpecification.hasEngagementRateBetween(filter.getMinEr(), filter.getMaxEr()));
    }

    // Claimed filter (OAuth)
    if (filter.getIsClaimed() != null) {
      exactSpec = exactSpec.and(PublicCreatorMetricSpecification.isClaimed(filter.getIsClaimed()));
    }

    // Fee range filter
    if (filter.getMinFee() != null || filter.getMaxFee() != null) {
      exactSpec = exactSpec.and(
          PublicCreatorMetricSpecification.hasFeeBetween(filter.getMinFee(), filter.getMaxFee()));
    }

    // Execute Exact Match
    List<com.influencermatch.backend.creator.model.PublicCreatorMetric> resExact =
        metricRepository.findAll(exactSpec, fetchPage).getContent();
    for (com.influencermatch.backend.creator.model.PublicCreatorMetric m : resExact) {
      if (seenCreatorIds.add(m.getCreator().getId())) {
        finalCandidates.add(m);
      }
    }

    // 3. SIMILAR FALLBACK (Mục 4.1: Nếu ít kết quả thì mở rộng Similar Fallback và có đánh dấu rõ)
    if (finalCandidates.size() < TARGET_CANDIDATES) {
      // Relax 1: Relax Location & Niche, keep hard platform + status
      Specification<com.influencermatch.backend.creator.model.PublicCreatorMetric> fallbackSpec1 =
          baseHardSpec.and(PublicCreatorMetricSpecification.notInCreatorIds(seenCreatorIds));
      if (filter.getTier() != null && !filter.getTier().isBlank()) {
        fallbackSpec1 = fallbackSpec1.and(PublicCreatorMetricSpecification.hasFollowerTier(filter.getTier()));
      }
      if (filter.getMinFee() != null || filter.getMaxFee() != null) {
        fallbackSpec1 = fallbackSpec1.and(
            PublicCreatorMetricSpecification.hasFeeBetween(filter.getMinFee(), filter.getMaxFee()));
      }

      List<com.influencermatch.backend.creator.model.PublicCreatorMetric> resFallback1 =
          metricRepository.findAll(fallbackSpec1, fetchPage).getContent();
      for (com.influencermatch.backend.creator.model.PublicCreatorMetric m : resFallback1) {
        if (finalCandidates.size() >= TARGET_CANDIDATES) break;
        if (seenCreatorIds.add(m.getCreator().getId())) {
          finalCandidates.add(m);
          fallbackCandidateIds.add(m.getCreator().getId());
        }
      }
    }

    if (finalCandidates.size() < TARGET_CANDIDATES) {
      // Relax 2: Relax follower tier entirely, keep hard platform + status
      Specification<com.influencermatch.backend.creator.model.PublicCreatorMetric> fallbackSpec2 =
          baseHardSpec.and(PublicCreatorMetricSpecification.notInCreatorIds(seenCreatorIds));

      List<com.influencermatch.backend.creator.model.PublicCreatorMetric> resFallback2 =
          metricRepository.findAll(fallbackSpec2, fetchPage).getContent();
      for (com.influencermatch.backend.creator.model.PublicCreatorMetric m : resFallback2) {
        if (finalCandidates.size() >= TARGET_CANDIDATES) break;
        if (seenCreatorIds.add(m.getCreator().getId())) {
          finalCandidates.add(m);
          fallbackCandidateIds.add(m.getCreator().getId());
        }
      }
    }

    // 4. Batch fetch recent posts
    List<UUID> candidateCreatorIds =
        finalCandidates.stream().map(m -> m.getCreator().getId()).toList();
    List<com.influencermatch.backend.creator.model.Post> allPosts =
        candidateCreatorIds.isEmpty()
            ? new ArrayList<>()
            : postRepository.findByCreatorIdIn(candidateCreatorIds);
    Map<UUID, List<com.influencermatch.backend.creator.model.Post>> postsByCreatorId =
        allPosts.stream()
            .collect(java.util.stream.Collectors.groupingBy(p -> p.getCreator().getId()));

    // 5. Map to CreatorDiscoveryResponse
    List<CreatorDiscoveryResponse> responses = new ArrayList<>();
    for (com.influencermatch.backend.creator.model.PublicCreatorMetric metric : finalCandidates) {
      FollowerFeeTier feeTier = FollowerFeeTier.fromFollowers(metric.getFollowers());
      BigDecimal feeMin = metric.getEstimatedFeeMin() != null ? metric.getEstimatedFeeMin() : feeTier.getMinFee();
      BigDecimal feeMax = metric.getEstimatedFeeMax() != null ? metric.getEstimatedFeeMax() : feeTier.getMaxFee();
      boolean isClaimed = metric.getCreator().getSourceType() == CreatorSourceType.AUTHORIZED
          || metric.getCreator().getAuthorizedAt() != null;
      boolean isFallback = fallbackCandidateIds.contains(metric.getCreator().getId());

      responses.add(
          CreatorDiscoveryResponse.builder()
              .creatorId(metric.getCreator().getId())
              .platform(metric.getCreator().getPlatform())
              .userName(metric.getCreator().getUserName())
              .profileUrl(metric.getCreator().getProfileUrl())
              .followers(metric.getFollowers())
              .engagementRate(metric.getEngagementRate())
              .niche(metric.getNiche())
              .location(metric.getCreator().getLocation())
              .contentSummary(metric.getContentSummary())
              .displayName(metric.getCreator().getDisplayName())
              .avatarUrl(metric.getCreator().getAvatarUrl())
              .creatorType(metric.getCreatorType())
              .estimatedFeeMin(feeMin)
              .estimatedFeeMax(feeMax)
              .actualFee(metric.getActualFee())
              .isClaimed(isClaimed)
              .isSimilarFallback(isFallback)
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
              .build());
    }

    // 6. SORTING (3 kiểu sort theo mục 4.2)
    String sortBy = filter.getSortBy() != null ? filter.getSortBy().trim().toUpperCase() : "SEMANTIC_RELEVANCE";

    BigDecimal budgetMin = campaign.getBudgetMin() != null ? campaign.getBudgetMin() : BigDecimal.ZERO;
    BigDecimal budgetMax = campaign.getBudgetMax() != null ? campaign.getBudgetMax() : BigDecimal.valueOf(Double.MAX_VALUE);
    BigDecimal targetBudgetMid = budgetMin.add(budgetMax).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);

    Comparator<CreatorDiscoveryResponse> comparator;
    if ("LOWEST_COMPARABLE_FEE".equals(sortBy)) {
      // Ưu tiên giá chính thức lên Top, rồi so sánh Comparable Fee tăng dần
      comparator = Comparator
          .comparing((CreatorDiscoveryResponse r) -> r.actualFee() != null ? 0 : 1)
          .thenComparing((CreatorDiscoveryResponse r) -> {
            if (r.actualFee() != null) return r.actualFee();
            return r.estimatedFeeMin().add(r.estimatedFeeMax()).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
          });
    } else if ("BUDGET_FIT".equals(sortBy)) {
      // Ưu tiên nằm trong budget [budgetMin, budgetMax], sau đó so sánh khoảng cách tới midpoint
      comparator = Comparator
          .comparing((CreatorDiscoveryResponse r) -> {
            BigDecimal fee = r.actualFee() != null
                ? r.actualFee()
                : r.estimatedFeeMin().add(r.estimatedFeeMax()).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
            boolean inBudget = fee.compareTo(budgetMin) >= 0 && fee.compareTo(budgetMax) <= 0;
            return inBudget ? 0 : 1;
          })
          .thenComparing((CreatorDiscoveryResponse r) -> {
            BigDecimal fee = r.actualFee() != null
                ? r.actualFee()
                : r.estimatedFeeMin().add(r.estimatedFeeMax()).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
            return fee.subtract(targetBudgetMid).abs();
          });
    } else {
      // SEMANTIC_RELEVANCE (hoặc mặc định): Ưu tiên Exact Match lên trước Fallback, sau đó sort theo ER giảm dần
      comparator = Comparator
          .comparing((CreatorDiscoveryResponse r) -> Boolean.TRUE.equals(r.isSimilarFallback()) ? 1 : 0)
          .thenComparing((CreatorDiscoveryResponse r) -> r.engagementRate() != null ? r.engagementRate() : BigDecimal.ZERO, Comparator.reverseOrder());
    }

    responses.sort(comparator);

    // 7. Slicing pagination
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
    Permissions.require(
        Permission.UPDATE_CAMPAIGN);
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
    Permissions.require(
        Permission.CHANGE_CAMPAIGN_STATUS);
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
    Permissions.require(
        Permission.CHANGE_CAMPAIGN_STATUS);
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
