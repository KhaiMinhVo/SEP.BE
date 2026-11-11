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
import com.influencermatch.backend.creator.dto.CreatorDiscoveryResponse;
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

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.brand.repository.BrandContextRepository brandContexts;

  private final CampaignContextRepository contexts;
  private final BrandService brandService;
  private final com.influencermatch.backend.billing.service.SubscriptionService subscriptionService;
  private final ObjectMapper objectMapper;
  private final PublicCreatorMetricRepository metricRepository;
  private final com.influencermatch.backend.creator.repository.PostRepository postRepository;

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
    Permissions.require(Permission.SEARCH_CREATOR);
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
                        .location(metric.getCreator().getLocation())
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
