package com.influencermatch.backend.collaboration.service;

import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.collaboration.dto.*;
import com.influencermatch.backend.collaboration.enums.*;
import com.influencermatch.backend.collaboration.event.CollaborationReviewedEvent;
import com.influencermatch.backend.collaboration.model.*;
import com.influencermatch.backend.collaboration.repository.*;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.repository.CreatorMasterRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.notification.enums.NotificationType;
import com.influencermatch.backend.notification.service.NotificationService;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor(onConstructor_ = {@org.springframework.beans.factory.annotation.Autowired})
public class CollaborationService {
  private final CampaignRepository campaigns;
  private final BrandCreatorRelationshipRepository relationships;
  private final CollaborationRepository collaborations;
  private final CampaignOutcomeRepository outcomes;
  private final CreatorReviewRepository reviews;
  private final CreatorMasterRepository creatorMasters;
  private final EvidenceAssetRepository evidenceAssets;
  private final EntityManager entityManager;
  private final Validator validator;
  private final ApplicationEventPublisher eventPublisher;
  private final NotificationService notificationService;

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.cooperation.DealService deals;
  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.cooperation.CollaborationExecution execution;

  public CollaborationService(
      CampaignRepository campaigns,
      BrandCreatorRelationshipRepository relationships,
      CollaborationRepository collaborations,
      CampaignOutcomeRepository outcomes,
      CreatorReviewRepository reviews,
      CreatorMasterRepository creatorMasters,
      EvidenceAssetRepository evidenceAssets,
      EntityManager entityManager,
      Validator validator) {
    this(
        campaigns,
        relationships,
        collaborations,
        outcomes,
        reviews,
        creatorMasters,
        evidenceAssets,
        entityManager,
        validator,
        null,
        null);
  }

  public CollaborationService(
      CampaignRepository campaigns,
      BrandCreatorRelationshipRepository relationships,
      CollaborationRepository collaborations,
      CampaignOutcomeRepository outcomes,
      CreatorReviewRepository reviews,
      CreatorMasterRepository creatorMasters,
      EvidenceAssetRepository evidenceAssets,
      EntityManager entityManager,
      Validator validator,
      ApplicationEventPublisher eventPublisher) {
    this(
        campaigns,
        relationships,
        collaborations,
        outcomes,
        reviews,
        creatorMasters,
        evidenceAssets,
        entityManager,
        validator,
        eventPublisher,
        null);
  }

  @Transactional
  @PreAuthorize("hasAuthority('MANAGE_OWN_COLLABORATION')")
  public CollaborationResponse create(UUID campaignId, CreateCollaborationRequest request) {
    return create(campaignId, request, null);
  }

  @Transactional
  public CollaborationResponse create(
      UUID campaignId, CreateCollaborationRequest request, String key) {
    Permissions.require(Permission.MANAGE_OWN_COLLABORATION);
    validate(request);

    if (deals != null) {
      var result = deals.confirmLegacy(campaignId, request, key);
      UUID id = UUID.fromString(result.path("collaborationId").asText());
      var c =
          collaborations
              .findById(id)
              .orElseThrow(() -> new NotFoundException("Collaboration not found"));

      if (notificationService != null
          && c.getCreator() != null
          && c.getCreator().getOwner() != null) {
        String brandName =
            c.getCampaign() != null && c.getCampaign().getBrandProfile() != null
                ? c.getCampaign().getBrandProfile().getBusinessName()
                : "Nhãn hàng";
        String feeDisplay = c.getAgreedFee() + " " + (c.getCurrency() != null ? c.getCurrency() : "VND");
        notificationService.createAndDispatch(
            c.getCreator().getOwner().getId(),
            NotificationType.COLLABORATION_RECORDED,
            "Hợp tác chiến dịch đã được ghi nhận",
            "Nhãn hàng " + brandName + " đã ghi nhận thỏa thuận hợp tác cho chiến dịch: "
                + (c.getCampaign() != null ? c.getCampaign().getName() : "") + " (Mức phí: " + feeDisplay + ")",
            "COLLABORATION",
            c.getId());
      }

      return new CollaborationResponse(
          c.getId(),
          c.getCampaign().getId(),
          c.getRelationship().getId(),
          c.getCreator().getId(),
          c.getAgreedFee(),
          c.getCurrency(),
          c.getStatus(),
          c.getPaymentStatus(),
          c.getPublishedContentUrl(),
          c.getNote(),
          c.getConfirmationSource(),
          c.getStartAt(),
          c.getCompletedAt(),
          c.getUpdatedAt());
    }

    Campaign campaign = ownedCampaign(campaignId);
    requireActive(campaign);
    BrandCreatorRelationship relationship = lockedRelationship(request.relationshipId());
    requireRelationshipOwner(relationship);
    if (!relationship.getCampaign().getId().equals(campaignId))
      throw new ValidationException("Relationship does not belong to this campaign");
    if (collaborations.existsByRelationshipId(relationship.getId()))
      throw conflict(
          ErrorCode.COLLABORATION_ALREADY_EXISTS, "This relationship already has a collaboration");
    if (relationship.getStage() != RelationshipStage.COLLABORATING
        && relationship.getStage() != RelationshipStage.INTERESTED
        && relationship.getStage() != RelationshipStage.CONTACTED)
      throw conflict(
          ErrorCode.INVALID_COLLABORATION_STATE,
          "Relationship must be COLLABORATING, INTERESTED, or CONTACTED to start collaboration");
    BigDecimal agreedFee =
        request.agreedFee() != null ? request.agreedFee() : relationship.getAgreedFee();
    if (agreedFee == null) {
      throw new ValidationException(
          "agreedFee is required (use 0 for barter or non-monetary collaborations)");
    }
    String currency = request.currency() != null ? request.currency() : relationship.getCurrency();
    if (currency != null && !currency.isBlank()) {
      try {
        Currency.getInstance(currency);
      } catch (IllegalArgumentException ex) {
        throw new ValidationException("currency must be a valid ISO currency code");
      }
    }
    if (request.note() == null || request.note().isBlank()) {
      throw new ValidationException(
          "Collaboration note is required to record agreement details, deliverables, or compensation terms");
    }
    String note = request.note().trim();
    com.influencermatch.backend.creator.model.CreatorMaster master = relationship.getMaster();
    if (master == null && relationship.getCreator() != null) {
      master = relationship.getCreator().getMaster();
    }
    LocalDateTime now = LocalDateTime.now();
    Collaboration collaboration =
        collaborations.save(
            Collaboration.builder()
                .campaign(campaign)
                .relationship(relationship)
                .creator(relationship.getCreator())
                .master(master)
                .agreedFee(agreedFee)
                .currency(currency)
                .note(note)
                .confirmationSource("BRAND_RECORDED_EXTERNAL")
                .status(CollaborationStatus.IN_PROGRESS)
                .paymentStatus(CollaborationPaymentStatus.PENDING)
                .startAt(now)
                .updatedAt(now)
                .build());
    relationship.setStage(RelationshipStage.COLLABORATING);
    relationship.setAgreedFee(agreedFee);
    relationship.setCurrency(currency);
    relationship.setUpdatedAt(now);

    if (notificationService != null
        && collaboration.getCreator() != null
        && collaboration.getCreator().getOwner() != null) {
      String brandName =
          campaign.getBrandProfile() != null
              ? campaign.getBrandProfile().getBusinessName()
              : "Nhãn hàng";
      String feeDisplay = agreedFee + " " + (currency != null ? currency : "VND");
      notificationService.createAndDispatch(
          collaboration.getCreator().getOwner().getId(),
          NotificationType.COLLABORATION_RECORDED,
          "Hợp tác chiến dịch đã được ghi nhận",
          "Nhãn hàng " + brandName + " đã ghi nhận thỏa thuận hợp tác cho chiến dịch: "
              + campaign.getName() + " (Mức phí: " + feeDisplay + ")",
          "COLLABORATION",
          collaboration.getId());
    }

    return new CollaborationResponse(
        collaboration.getId(),
        campaignId,
        relationship.getId(),
        collaboration.getCreator().getId(),
        collaboration.getAgreedFee(),
        collaboration.getCurrency(),
        collaboration.getStatus(),
        collaboration.getPaymentStatus(),
        collaboration.getPublishedContentUrl(),
        collaboration.getNote(),
        collaboration.getConfirmationSource(),
        collaboration.getStartAt(),
        collaboration.getCompletedAt(),
        collaboration.getUpdatedAt());
  }

  @Transactional
  @PreAuthorize("hasAuthority('RECORD_OWN_OUTCOME')")
  public OutcomeResponse recordOutcome(UUID collaborationId, RecordOutcomeRequest request) {
    Permissions.require(Permission.RECORD_OWN_OUTCOME);
    validate(request);
    Collaboration collaboration = ownedLockedCollaboration(collaborationId);
    requireActive(collaboration.getCampaign());
    requireLegacyExecution(collaboration);
    if (outcomes.existsByCollaborationId(collaborationId))
      throw conflict(ErrorCode.OUTCOME_ALREADY_EXISTS, "Outcome is already recorded");
    if (collaboration.getStatus() != CollaborationStatus.IN_PROGRESS)
      throw conflict(
          ErrorCode.INVALID_COLLABORATION_STATE,
          "Only an IN_PROGRESS collaboration can be completed");
    BrandCreatorRelationship relationship =
        lockedRelationship(collaboration.getRelationship().getId());
    requireConsistentRelationship(collaboration, relationship);
    String finalPostUrl =
        (request.publishedContentUrl() != null && !request.publishedContentUrl().isBlank())
            ? request.publishedContentUrl().trim()
            : collaboration.getPublishedContentUrl();

    if (finalPostUrl == null || finalPostUrl.isBlank()) {
      throw new BadRequestException("Published content URL is required");
    }

    LocalDateTime now = LocalDateTime.now();
    CampaignOutcome outcome =
        outcomes.save(
            CampaignOutcome.builder()
                .collaboration(collaboration)
                .views(request.views())
                .clicks(request.clicks())
                .likes(request.likes())
                .comments(request.comments())
                .shares(request.shares())
                .resultNote(request.resultNote())
                .evidenceQuality("SELF_REPORTED")
                .recordedAt(now)
                .build());
    collaboration.setPublishedContentUrl(finalPostUrl);
    // Recording evidence never completes execution. Acceptance and final review are separate.
    collaboration.setUpdatedAt(now);
    // CRM confirmation is separate from execution completion.
    relationship.setStage(RelationshipStage.CONFIRMED);
    relationship.setUpdatedAt(now);
    return new OutcomeResponse(
        outcome.getId(),
        collaborationId,
        outcome.getViews(),
        outcome.getClicks(),
        outcome.getLikes(),
        outcome.getComments(),
        outcome.getShares(),
        collaboration.getPublishedContentUrl(),
        outcome.getResultNote(),
        "SELF_REPORTED",
        now,
        null);
  }

  @Transactional
  @PreAuthorize("hasAuthority('RECORD_OWN_CREATOR_REVIEW')")
  public CreatorReviewResponse review(UUID collaborationId, CreateCreatorReviewRequest request) {
    Permissions.require(Permission.RECORD_OWN_CREATOR_REVIEW);
    validate(request);
    Collaboration collaboration = ownedLockedCollaboration(collaborationId);
    requireLegacyExecution(collaboration);
    CampaignStatus status = collaboration.getCampaign().getStatus();
    if ((status != CampaignStatus.ACTIVE && status != CampaignStatus.COMPLETED)
        || collaboration.getStatus() != CollaborationStatus.COMPLETED)
      throw conflict(
          ErrorCode.INVALID_COLLABORATION_STATE,
          "Review requires a completed collaboration in an ACTIVE or COMPLETED campaign");
    CampaignOutcome outcome =
        outcomes
            .findByCollaborationId(collaborationId)
            .orElseThrow(
                () ->
                    conflict(ErrorCode.INVALID_COLLABORATION_STATE, "Review requires an outcome"));
    if (reviews.existsByCollaborationId(collaborationId))
      throw conflict(ErrorCode.CREATOR_REVIEW_ALREADY_EXISTS, "Review is already recorded");
    CreatorReview review =
        reviews.save(
            CreatorReview.builder()
                .collaboration(collaboration)
                .outcome(outcome)
                .reviewedBy(Permissions.actor())
                .overallRating(request.overallRating().shortValue())
                .brandFitRating(request.brandFitRating().shortValue())
                .wouldCollaborateAgain(request.wouldCollaborateAgain())
                .note(request.note())
                .reviewedAt(LocalDateTime.now())
                .build());

    if (eventPublisher != null) {
      UUID masterId =
          collaboration.getMaster() != null
              ? collaboration.getMaster().getId()
              : (collaboration.getCreator() != null
                      && collaboration.getCreator().getMaster() != null
                  ? collaboration.getCreator().getMaster().getId()
                  : null);
      UUID brandProfileId =
          (collaboration.getCampaign() != null
                  && collaboration.getCampaign().getBrandProfile() != null)
              ? collaboration.getCampaign().getBrandProfile().getId()
              : null;
      eventPublisher.publishEvent(
          new CollaborationReviewedEvent(
              collaboration.getId(),
              review.getId(),
              outcome.getId(),
              collaboration.getCampaign() != null ? collaboration.getCampaign().getId() : null,
              brandProfileId,
              collaboration.getCreator() != null ? collaboration.getCreator().getId() : null,
              masterId,
              collaboration.getCreator() != null ? collaboration.getCreator().getPlatform() : null,
              review.getOverallRating(),
              review.getBrandFitRating(),
              review.getWouldCollaborateAgain(),
              review.getNote()));
    }
    return new CreatorReviewResponse(
        review.getId(),
        collaborationId,
        outcome.getId(),
        review.getReviewedBy().getId(),
        review.getOverallRating(),
        review.getBrandFitRating(),
        review.getWouldCollaborateAgain(),
        review.getNote(),
        review.getReviewedAt());
  }

  private boolean hasRecordedOutcome(Collaboration c) {
    if (outcomes.existsByCollaborationId(c.getId())) return true;
    if ("LEGACY".equals(c.getExecutionSource())) return false;
    return ((Number)
                entityManager
                    .createNativeQuery(
                        "select count(*) from collaboration_outcome_revision where"
                            + " collaboration_id=:id")
                    .setParameter("id", c.getId())
                    .getSingleResult())
            .longValue()
        > 0;
  }

  private void requireLegacyExecution(Collaboration collaboration) {
    if (!"LEGACY".equals(collaboration.getExecutionSource()))
      throw new BusinessException(
          ErrorCode.INVALID_COLLABORATION_STATE, "Use the revision-aware execution workflow");
  }

  @Transactional(readOnly = true)
  @PreAuthorize("hasAuthority('MANAGE_OWN_COLLABORATION')")
  public PageResponse<CollaborationSummaryDto> getCampaignCollaborations(
      UUID campaignId, CollaborationStatus status, Pageable pageable) {
    if (Permissions.actor().getRole() != Role.BRAND)
      throw new ForbiddenException("Brand required");
    Permissions.require(Permission.MANAGE_OWN_COLLABORATION);
    if (deals != null) deals.fresh(Permission.MANAGE_OWN_COLLABORATION);
    Campaign campaign =
        campaigns
            .findById(campaignId)
            .orElseThrow(() -> new NotFoundException("Campaign not found"));
    if (!campaign.getBrandProfile().getUser().getId().equals(Permissions.actor().getId())) {
      throw new ForbiddenException("Campaign belongs to another brand");
    }

    Page<Collaboration> page =
        (status == null)
            ? collaborations.findByCampaignId(campaignId, pageable)
            : collaborations.findByCampaignIdAndStatus(campaignId, status, pageable);

    List<CollaborationSummaryDto> items =
        page.getContent().stream()
            .map(
                c -> {
                  boolean hasOutcome = hasRecordedOutcome(c);
                  boolean hasReview = reviews.existsByCollaborationId(c.getId());
                  String creatorName =
                      c.getCreator().getDisplayName() != null
                              && !c.getCreator().getDisplayName().isBlank()
                          ? c.getCreator().getDisplayName()
                          : c.getCreator().getUserName();
                  return new CollaborationSummaryDto(
                      c.getId(),
                      campaignId,
                      c.getRelationship().getId(),
                      c.getCreator().getId(),
                      creatorName,
                      c.getCreator().getPlatform(),
                      c.getCreator().getAvatarUrl(),
                      c.getAgreedFee(),
                      c.getCurrency(),
                      c.getStatus(),
                      c.getPublishedContentUrl(),
                      c.getNote(),
                      c.getStartAt(),
                      c.getCompletedAt(),
                      c.getUpdatedAt(),
                      hasOutcome,
                      hasReview,
                      deals.channels(c.getId()));
                })
            .toList();

    return new PageResponse<>(
        items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  @Transactional(readOnly = true)
  public CollaborationDetailResponse getCollaborationDetail(UUID collaborationId) {
    Collaboration c =
        collaborations
            .findById(collaborationId)
            .orElseThrow(() -> new NotFoundException("Collaboration not found"));
    boolean brand = requireCollaborationAccess(c);

    OutcomeResponse outcomeDto =
        outcomes
            .findByCollaborationId(collaborationId)
            .map(
                o ->
                    new OutcomeResponse(
                        o.getId(),
                        collaborationId,
                        o.getViews(),
                        o.getClicks(),
                        o.getLikes(),
                        o.getComments(),
                        o.getShares(),
                        c.getPublishedContentUrl(),
                        brand ? o.getResultNote() : null,
                        o.getEvidenceQuality(),
                        o.getRecordedAt(),
                        o.getCompletedAt()))
            .orElse(null);

    CreatorReviewResponse reviewDto =
        !brand ? null : reviews
            .findByCollaborationId(collaborationId)
            .map(
                r ->
                    new CreatorReviewResponse(
                        r.getId(),
                        collaborationId,
                        r.getOutcome().getId(),
                        r.getReviewedBy().getId(),
                        r.getOverallRating(),
                        r.getBrandFitRating(),
                        r.getWouldCollaborateAgain(),
                        r.getNote(),
                        r.getReviewedAt()))
            .orElse(null);

    List<EvidenceAssetResponse> evidenceList = evidenceFor(c, brand);

    String creatorName =
        c.getCreator().getDisplayName() != null && !c.getCreator().getDisplayName().isBlank()
            ? c.getCreator().getDisplayName()
            : c.getCreator().getUserName();

    return new CollaborationDetailResponse(
        c.getId(),
        c.getCampaign().getId(),
        c.getCampaign().getName(),
        c.getCampaign().getBrandProfile().getBusinessName(),
        c.getRelationship().getId(),
        c.getCreator().getId(),
        creatorName,
        c.getCreator().getPlatform(),
        c.getCreator().getAvatarUrl(),
        c.getCreator().getProfileUrl(),
        c.getAgreedFee(),
        c.getCurrency(),
        c.getStatus(),
        c.getPublishedContentUrl(),
        brand ? c.getNote() : null,
        c.getStartAt(),
        c.getCompletedAt(),
        c.getUpdatedAt(),
        outcomeDto,
        reviewDto,
        evidenceList,
        deals != null ? deals.channels(c.getId()) : Collections.emptyList());
  }

  @Transactional
  public EvidenceAssetResponse submitEvidence(UUID collaborationId, SubmitEvidenceRequest request) {
    validate(request);
    if (Permissions.actor().getRole() != Role.CREATOR)
      throw new ForbiddenException("Only Creator may attach collaboration evidence");
    if (execution != null)
      execution.progress(execution.lock(collaborationId, false, Permission.RESPOND_OWN_COOPERATION));
    Collaboration collaboration =
        collaborations
            .findById(collaborationId)
            .orElseThrow(() -> new NotFoundException("Collaboration not found"));

    requireCollaborationAccess(collaboration);
    if (collaboration.getStatus() != CollaborationStatus.IN_PROGRESS
        || collaboration.getCampaign().getStatus() != CampaignStatus.ACTIVE)
      throw new BusinessException(ErrorCode.DEAL_STATE_CONFLICT, "An active collaboration is required");
    String source = "CREATOR_SUBMITTED";
    LocalDateTime now = LocalDateTime.now();

    CampaignOutcome outcome = outcomes.findByCollaborationId(collaborationId).orElse(null);

    EvidenceAsset asset =
        evidenceAssets
            .findFirstByCollaborationIdAndAssetTypeAndStorageUrl(
                collaborationId, request.assetType(), request.storageUrl())
            .orElse(null);
    if (asset != null && !source.equals(asset.getSource()))
      throw new BusinessException(ErrorCode.DEAL_STATE_CONFLICT, "Evidence link is already registered");

    boolean isNewAsset = (asset == null);
    if (isNewAsset) {
      asset =
          evidenceAssets.save(
              EvidenceAsset.builder()
                  .collaboration(collaboration)
                  .outcome(outcome)
                  .assetType(request.assetType())
                  .storageUrl(request.storageUrl())
                  .source(source)
                  .uploadedAt(now)
                  .build());
    }

    if (isNewAsset
        && notificationService != null
        && collaboration.getCampaign() != null
        && collaboration.getCampaign().getBrandProfile() != null
        && collaboration.getCampaign().getBrandProfile().getUser() != null) {
      UUID brandUserId = collaboration.getCampaign().getBrandProfile().getUser().getId();
      notificationService.createAndDispatch(
          brandUserId,
          NotificationType.EVIDENCE_SUBMITTED,
          "Creator đã nộp bài nghiệm thu",
          "Creator đã nộp nội dung nghiệm thu cho chiến dịch: "
              + collaboration.getCampaign().getName(),
          "EVIDENCE_ASSET",
          asset.getId());
    }

    return new EvidenceAssetResponse(
        asset.getId(),
        collaborationId,
        outcome != null ? outcome.getId() : null,
        asset.getAssetType(),
        asset.getStorageUrl(),
        asset.getSource(),
        asset.getUploadedAt());
  }

  @Transactional(readOnly = true)
  public List<EvidenceAssetResponse> getEvidenceAssets(UUID collaborationId) {
    Collaboration collaboration =
        collaborations
            .findById(collaborationId)
            .orElseThrow(() -> new NotFoundException("Collaboration not found"));

    return evidenceFor(collaboration, requireCollaborationAccess(collaboration));
  }

  private boolean requireCollaborationAccess(Collaboration collaboration) {
    User actor = Permissions.actor();
    boolean brand = actor.getRole() == Role.BRAND;
    Permission permission = brand ? Permission.MANAGE_OWN_COLLABORATION : Permission.RESPOND_OWN_COOPERATION;
    if (!brand && actor.getRole() != Role.CREATOR)
      throw new ForbiddenException("Collaboration role required");
    Permissions.require(permission);
    if (deals != null) deals.fresh(permission);
    boolean allowed = brand
        ? collaboration.getCampaign().getBrandProfile().getUser().getId().equals(actor.getId())
        : deals != null
            ? deals.canAccessCollaboration(actor.getId(), collaboration.getId())
            : collaboration.getCreator() != null && collaboration.getCreator().getOwner() != null
                && collaboration.getCreator().getOwner().getId().equals(actor.getId());
    if (!allowed) throw new ForbiddenException("Collaboration ownership required");
    return brand;
  }

  private List<EvidenceAssetResponse> evidenceFor(Collaboration collaboration, boolean brand) {
    UUID collaborationId = collaboration.getId();
    return evidenceAssets.findByCollaborationIdOrderByUploadedAtDesc(collaborationId).stream()
        .filter(asset -> brand || "CREATOR_SUBMITTED".equals(asset.getSource()))
        .map(
            a ->
                new EvidenceAssetResponse(
                    a.getId(),
                    collaborationId,
                    a.getOutcome() != null ? a.getOutcome().getId() : null,
                    a.getAssetType(),
                    a.getStorageUrl(),
                    a.getSource(),
                    a.getUploadedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION')")
  public List<CreatorCollaborationDto> getCreatorPortalCollaborations(CollaborationStatus status) {
    User actor = Permissions.actor();
    if (actor.getRole() != Role.CREATOR) {
      throw new ForbiddenException("Only creators can access creator portal collaborations");
    }

    Permissions.require(Permission.RESPOND_OWN_COOPERATION);
    if (deals != null) deals.fresh(Permission.RESPOND_OWN_COOPERATION);

    CreatorMaster master = creatorMasters.findByUserId(actor.getId()).orElse(null);
    if (master == null) {
      return List.of();
    }

    List<Collaboration> list =
        (status == null)
            ? collaborations.findByMasterId(master.getId())
            : collaborations.findByMasterIdAndStatus(master.getId(), status);

    return list.stream()
        .filter(
            c ->
                deals != null
                    ? deals.canAccessCollaboration(actor.getId(), c.getId())
                    : (c.getCreator() != null
                        && c.getCreator().getOwner() != null
                        && c.getCreator().getOwner().getId().equals(actor.getId())))
        .map(
            c -> {
              boolean hasOutcome = hasRecordedOutcome(c);
              return new CreatorCollaborationDto(
                  c.getId(),
                  c.getCampaign().getId(),
                  c.getCampaign().getName(),
                  c.getCampaign().getBrandProfile().getBusinessName(),
                  c.getCreator().getId(),
                  c.getCreator().getPlatform(),
                  c.getCreator().getUserName(),
                  c.getAgreedFee(),
                  c.getCurrency(),
                  c.getStatus(),
                  c.getPublishedContentUrl(),
                  null,
                  c.getStartAt(),
                  c.getCompletedAt(),
                  c.getUpdatedAt(),
                  hasOutcome,
                  false,
                  deals != null ? deals.channels(c.getId()) : Collections.emptyList());
            })
        .toList();
  }

  private Campaign ownedCampaign(UUID id) {
    Campaign campaign =
        campaigns
            .findLockedForCollaborationById(id)
            .orElseThrow(() -> new NotFoundException("Campaign not found"));
    // Keep lifecycle writes from racing against collaboration creation or acceptance.
    entityManager.refresh(campaign);
    if (!campaign.getBrandProfile().getUser().getId().equals(Permissions.actor().getId()))
      throw new ForbiddenException("Campaign belongs to another brand");
    return campaign;
  }

  private Collaboration ownedLockedCollaboration(UUID id) {
    Collaboration initial =
        collaborations
            .findById(id)
            .orElseThrow(() -> new NotFoundException("Collaboration not found"));
    ownedCampaign(initial.getCampaign().getId());
    Collaboration collaboration =
        collaborations
            .findLockedById(id)
            .orElseThrow(() -> new NotFoundException("Collaboration not found"));
    // Reload state after any wait on the lock, even when already managed by Hibernate.
    entityManager.refresh(collaboration);
    requireConsistentRelationship(collaboration, collaboration.getRelationship());
    return collaboration;
  }

  private BrandCreatorRelationship lockedRelationship(UUID id) {
    BrandCreatorRelationship relationship =
        relationships
            .findLockedById(id)
            .orElseThrow(() -> new NotFoundException("Relationship not found"));
    entityManager.refresh(relationship);
    return relationship;
  }

  private void requireRelationshipOwner(BrandCreatorRelationship relationship) {
    if (!relationship.getUser().getId().equals(Permissions.actor().getId()))
      throw new ForbiddenException("Relationship belongs to another brand");
  }

  private void requireConsistentRelationship(
      Collaboration collaboration, BrandCreatorRelationship relationship) {
    requireRelationshipOwner(relationship);
    if (!relationship.getCampaign().getId().equals(collaboration.getCampaign().getId())
        || !relationship.getCreator().getId().equals(collaboration.getCreator().getId()))
      throw conflict(
          ErrorCode.INVALID_COLLABORATION_STATE, "Collaboration relationships are inconsistent");
  }

  private void requireActive(Campaign campaign) {
    if (campaign.getStatus() != CampaignStatus.ACTIVE)
      throw conflict(ErrorCode.INVALID_COLLABORATION_STATE, "Campaign must be ACTIVE");
  }

  private void validate(Object request) {
    var violations = validator.validate(request);
    if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
  }

  private ConflictException conflict(ErrorCode code, String message) {
    return new ConflictException(code, message);
  }
}
