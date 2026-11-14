package com.influencermatch.backend.campaign.service;

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
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignService {
  private final CampaignRepository campaigns;

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.brand.repository.BrandContextRepository brandContexts;

  private final CampaignContextRepository contexts;
  private final BrandService brandService;
  private final com.influencermatch.backend.billing.service.SubscriptionService subscriptionService;
  private final ObjectMapper objectMapper;
  private final PublicCreatorMetricRepository metricRepository;
  private final com.influencermatch.backend.creator.repository.PostRepository postRepository;

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.discovery.service.CreatorDiscoveryService discovery;


  @org.springframework.beans.factory.annotation.Autowired private CampaignWriteLock writeLocks;
  @org.springframework.beans.factory.annotation.Autowired private CampaignM3ContextService m3;

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore embeddings;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.jdbc.core.JdbcTemplate executionDb;

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CREATE_CAMPAIGN')")
  public CampaignResponse create(
      UUID brandId, CampaignRequest request, Authentication authentication) {
    Permissions.require(Permission.CREATE_CAMPAIGN);
    BrandProfile brandProfile = brandService.requireAccessible(brandId, authentication);
    validate(request);
    subscriptionService.checkAndDeductCampaignQuota(brandProfile.getId());
    Campaign campaign =
        Campaign.builder()
            .brandProfile(brandProfile)
            .name(request.name().trim())
            .productService(
                com.influencermatch.backend.brand.service.BrandContextData.text(
                    request.productService()))
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
    Permissions.require(Permission.VIEW_OWN_CAMPAIGN, Permission.VIEW_BRAND_SUPPORT_DATA);
    brandService.requireAccessible(brandId, authentication);
    return PageResponse.from(
        campaigns.findByBrandProfileId(brandId, pageable).map(this::loadResponse));
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_OWN_CAMPAIGN') or hasAuthority('VIEW_BRAND_SUPPORT_DATA')")
  public CampaignResponse get(UUID id, Authentication authentication) {
    Permissions.require(Permission.VIEW_OWN_CAMPAIGN, Permission.VIEW_BRAND_SUPPORT_DATA);
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

    var request = com.influencermatch.backend.discovery.dto.DiscoverySearchRequest.builder()
        .keyword(filter.getKeyword()).platform(filter.getPlatform()).niche(filter.getNiche())
        .minFollowers(filter.getMinFollowers()).maxFollowers(filter.getMaxFollowers())
        .tier(filter.getTier()).minEr(filter.getMinEr()).maxEr(filter.getMaxEr())
        .isClaimed(filter.getIsClaimed()).minFee(filter.getMinFee()).maxFee(filter.getMaxFee())
        .sortBy(filter.getSortBy()).page(0).size(50).build();
    var ranked = discovery.discoverCreators(campaignId, request);
    var metricIds = ranked.getContent().stream().map(p -> p.getId()).toList();
    var byId = metricRepository.findAllById(metricIds).stream().collect(
        java.util.stream.Collectors.toMap(m -> m.getId(), m -> m));
    var finalCandidates = metricIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    Map<UUID, Integer> candidateOrder = new HashMap<>();
    for (int i = 0; i < finalCandidates.size(); i++)
      candidateOrder.put(finalCandidates.get(i).getCreator().getId(), i);

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

      responses.add(
          CreatorDiscoveryResponse.builder()
              .creatorId(metric.getCreator().getId())
              .platform(metric.getCreator().getPlatform())
              .userName(metric.getCreator().getUserName())
              .profileUrl(metric.getCreator().getProfileUrl())
              .followers(metric.getFollowers())
              .followerChange7d(metric.getFollowerChange7d())
              .followerGrowthRate(metric.getFollowerGrowthRate())
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
              .isSimilarFallback(false)
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
    String sortBy = filter.getSortBy() != null ? filter.getSortBy().trim().toUpperCase() : "DATABASE_RELEVANCE";

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
      // Preserve database keyword relevance; AI scores are computed after shortlisting.
      comparator = Comparator.comparingInt(r -> candidateOrder.get(r.creatorId()));
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
  public CampaignResponse update(
      UUID id, CampaignRequest request, Authentication authentication, String revision) {
    Permissions.require(Permission.UPDATE_CAMPAIGN);
    Campaign campaign = writeLocks.owned(id);
    CampaignRevision.require(revision, id, campaign.getCampaignRevision());
    campaign.getStatus().requireEditable();
    validate(request);
    var before = CampaignBriefData.snapshot(campaign, objectMapper);
    applyCampaign(campaign, request);
    if (!before.equals(CampaignBriefData.snapshot(campaign, objectMapper)))
      campaign.setCampaignRevision(campaign.getCampaignRevision() + 1);
    return response(campaign);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('CHANGE_CAMPAIGN_STATUS')")
  public CampaignResponse archive(UUID id, Authentication authentication, String revision) {
    Permissions.require(Permission.CHANGE_CAMPAIGN_STATUS);
    Campaign campaign = writeLocks.owned(id);
    CampaignRevision.require(revision, id, campaign.getCampaignRevision());
    if (campaign.getStatus() != CampaignStatus.ARCHIVED) {
      campaign.getStatus().requireTransitionTo(CampaignStatus.ARCHIVED);
      campaign.setStatus(CampaignStatus.ARCHIVED);
      campaign.setCampaignRevision(campaign.getCampaignRevision() + 1);
    }
    return loadResponse(campaign);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('CHANGE_CAMPAIGN_STATUS')")
  public CampaignResponse changeStatus(
      UUID id, CampaignStatusRequest request, Authentication authentication, String revision) {
    Permissions.require(Permission.CHANGE_CAMPAIGN_STATUS);
    Campaign campaign = writeLocks.owned(id);
    CampaignRevision.require(revision, id, campaign.getCampaignRevision());
    CampaignStatus target = request.status();
    campaign.getStatus().requireTransitionTo(target);
    if (target == CampaignStatus.READY_FOR_DISCOVERY) {
      validateReady(campaign);
      m3.create(campaign, false);
    }
    if (target == CampaignStatus.COMPLETED) {
      Long completed =
          executionDb.queryForObject(
              "select count(*) from collaboration where campaign_id=? and status='COMPLETED'",
              Long.class,
              id);
      Long unfinished =
          executionDb.queryForObject(
              "select count(*) from collaboration where campaign_id=? and status not in"
                  + " ('COMPLETED','CANCELLED')",
              Long.class,
              id);
      Long openDeals =
          executionDb.queryForObject(
              "select count(*) from cooperation_deal where campaign_id=? and status in"
                  + " ('CONTACTED','NEGOTIATING')",
              Long.class,
              id);
      if (completed == 0 || unfinished > 0 || openDeals > 0)
        throw new BusinessException(
            ErrorCode.INVALID_COLLABORATION_STATE,
            "Resolve open deals and finish collaborations before closing Campaign");
    }
    campaign.setStatus(target);
    campaign.setCampaignRevision(campaign.getCampaignRevision() + 1);
    return loadResponse(campaign);
  }

  private Campaign requireCampaign(UUID id) {
    return campaigns.findById(id).orElseThrow(() -> notFound("Campaign not found: " + id));
  }

  public CampaignResponse update(UUID id, CampaignRequest r, Authentication a) {
    return update(id, r, a, null);
  }

  public CampaignResponse changeStatus(UUID id, CampaignStatusRequest r, Authentication a) {
    return changeStatus(id, r, a, null);
  }

  public CampaignResponse archive(UUID id, Authentication a) {
    return archive(id, a, null);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('UPDATE_CAMPAIGN')")
  public CampaignResponse retryEmbedding(UUID id, Authentication a) {
    Permissions.require(Permission.UPDATE_CAMPAIGN);
    var c = writeLocks.owned(id);
    var ctx = m3.latest(id);
    if (ctx == null || ctx.getEmbeddingSourceContextId() == null)
      throw new ConflictException(
          ErrorCode.CAMPAIGN_EMBEDDING_RETRY_CONFLICT, "No tracked current embedding job");
    embeddings.retry(ctx.getEmbeddingSourceContextId());
    return response(c);
  }

  private void validate(CampaignRequest r) {
    if (r.budgetMin() != null || r.budgetMax() != null)
      throw new ValidationException(
          "budgetMin/budgetMax are legacy read-only fields; use totalBudget and"
              + " creatorFeeMin/creatorFeeMax");
    if (r.startDate() != null && r.endDate() != null && r.endDate().isBefore(r.startDate()))
      invalid("endDate must not be before startDate");
    if (greater(r.followerMin(), r.followerMax())) invalid("Invalid follower range");
    if (greater(r.creatorFeeMin(), r.creatorFeeMax())) invalid("Invalid Creator fee range");
    if (r.creatorMode() == CreatorMode.SINGLE
        && r.targetCreatorCount() != null
        && r.targetCreatorCount() != 1) invalid("SINGLE requires one Creator");
    if (r.creatorMode() == CreatorMode.MULTI
        && r.targetCreatorCount() != null
        && r.targetCreatorCount() < 2) invalid("MULTI requires at least two Creators");
    if (r.deliverables() != null)
      for (var d : r.deliverables())
        if (r.platforms() == null || !r.platforms().contains(d.platform()))
          invalid("Deliverable platform must belong to Campaign platforms");
  }

  private void validateReady(Campaign c) {
    var missing = new LinkedHashMap<String, List<String>>();
    for (var e :
        Map.of(
                "productService",
                Objects.toString(c.getProductService(), ""),
                "brief",
                Objects.toString(c.getBrief(), ""))
            .entrySet())
      if (e.getValue().isBlank()) missing.put(e.getKey(), List.of("Required before READY"));
    if (c.getObjective() == null) missing.put("objective", List.of("Required"));
    if (c.getPlatforms().isEmpty()) missing.put("platforms", List.of("At least one required"));
    if (c.getStartDate() == null) missing.put("startDate", List.of("Required"));
    if (c.getEndDate() == null) missing.put("endDate", List.of("Required"));
    if (c.getTotalBudget() == null) missing.put("totalBudget", List.of("Required"));
    if (c.getCreatorMode() == null) missing.put("creatorMode", List.of("Required"));
    if (c.getTargetCreatorCount() == null) missing.put("targetCreatorCount", List.of("Required"));
    if (c.getDeliverables().isEmpty())
      missing.put("deliverables", List.of("At least one required"));
    if (!missing.isEmpty()) throw new ProfileIncompleteException(missing);
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
    c.setProductService(BrandContextData.text(r.productService()));
    c.setObjective(r.objective());
    c.setTargetAudiences(strings(r.targetAudiences()));
    c.setContentType(BrandContextData.text(r.contentType()));
    c.setStartDate(r.startDate());
    c.setEndDate(r.endDate());
    c.setPlatforms(
        r.platforms() == null
            ? List.of()
            : r.platforms().stream().map(Enum::name).distinct().sorted().toList());
    c.setNiches(strings(r.niches()));
    c.setLocations(strings(r.locations()));
    c.setFollowerMin(r.followerMin());
    c.setFollowerMax(r.followerMax());
    c.setBrief(BrandContextData.text(r.brief()));
    c.setCreatorMode(r.creatorMode());
    c.setTargetCreatorCount(r.targetCreatorCount());
    c.setDeliverables(
        r.deliverables() == null
            ? List.of()
            : r.deliverables().stream()
                .map(
                    d ->
                        new CampaignDeliverable(d.platform(), d.contentType().trim(), d.quantity()))
                .distinct()
                .sorted(
                    Comparator.comparing((CampaignDeliverable d) -> d.platform().name())
                        .thenComparing(CampaignDeliverable::contentType)
                        .thenComparing(CampaignDeliverable::quantity))
                .toList());
    c.setTotalBudget(r.totalBudget());
    c.setCurrency(r.currency() == null ? "VND" : r.currency());
    c.setCreatorFeeMin(r.creatorFeeMin());
    c.setCreatorFeeMax(r.creatorFeeMax());
  }

  private List<String> strings(List<String> values) {
    return BrandContextData.list(values);
  }

  private CampaignResponse loadResponse(Campaign c) {
    return response(c);
  }

  private CampaignResponse response(Campaign c) {
    var ctx = m3.latest(c.getId());
    var view =
        ctx == null || ctx.getEmbeddingSourceContextId() == null
            ? null
            : embeddings.view(ctx.getEmbeddingSourceContextId());
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
        c.getUpdatedAt(),
        c.getBrief(),
        c.getCreatorMode(),
        c.getTargetCreatorCount(),
        c.getDeliverables(),
        c.getTotalBudget(),
        c.getCurrency(),
        c.getCreatorFeeMin(),
        c.getCreatorFeeMax(),
        "UNAVAILABLE",
        c.getCampaignRevision(),
        ctx == null ? null : ctx.getContextVersion(),
        ctx == null ? null : ctx.getEmbeddingSourceContextId(),
        view == null ? "NOT_REQUESTED" : view.status(),
        view == null ? null : view.model(),
        view == null ? null : view.modelVersion(),
        view == null ? null : view.readyAt());
  }

  private BusinessException notFound(String detail) {
    return new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND, detail);
  }
}
