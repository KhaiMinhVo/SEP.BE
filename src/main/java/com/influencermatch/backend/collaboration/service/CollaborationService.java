package com.influencermatch.backend.collaboration.service;

import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.collaboration.dto.*;
import com.influencermatch.backend.collaboration.enums.*;
import com.influencermatch.backend.collaboration.model.*;
import com.influencermatch.backend.collaboration.repository.*;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.repository.CreatorMasterRepository;
import com.influencermatch.backend.exception.*;
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
import com.influencermatch.backend.collaboration.event.CollaborationReviewedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
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
        null);
  }

  @Transactional
  @PreAuthorize("hasAuthority('MANAGE_OWN_COLLABORATION')")
  public CollaborationResponse create(UUID campaignId, CreateCollaborationRequest request) {
    Permissions.require(Permission.MANAGE_OWN_COLLABORATION);
    validate(request);
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
    String currency = request.currency() != null ? request.currency() : relationship.getCurrency();
    if (currency != null && !currency.isBlank()) {
      try {
        Currency.getInstance(currency);
      } catch (IllegalArgumentException ex) {
        throw new ValidationException("currency must be a valid ISO currency code");
      }
    }
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
                .note(request.note())
                .status(CollaborationStatus.IN_PROGRESS)
                .paymentStatus(CollaborationPaymentStatus.PENDING)
                .startAt(now)
                .updatedAt(now)
                .build());
    relationship.setStage(RelationshipStage.COLLABORATING);
    relationship.setAgreedFee(agreedFee);
    relationship.setCurrency(currency);
    relationship.setUpdatedAt(now);
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
                .completedAt(now)
                .build());
    collaboration.setPublishedContentUrl(finalPostUrl);
    collaboration.setStatus(CollaborationStatus.COMPLETED);
    collaboration.setCompletedAt(now);
    collaboration.setUpdatedAt(now);
    relationship.setStage(RelationshipStage.COMPLETED);
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
        now);
  }

  @Transactional
  @PreAuthorize("hasAuthority('RECORD_OWN_CREATOR_REVIEW')")
  public CreatorReviewResponse review(UUID collaborationId, CreateCreatorReviewRequest request) {
    Permissions.require(Permission.RECORD_OWN_CREATOR_REVIEW);
    validate(request);
    Collaboration collaboration = ownedLockedCollaboration(collaborationId);
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
              : (collaboration.getCreator() != null && collaboration.getCreator().getMaster() != null
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

  @Transactional(readOnly = true)
  @PreAuthorize("hasAuthority('MANAGE_OWN_COLLABORATION')")
  public PageResponse<CollaborationSummaryDto> getCampaignCollaborations(
      UUID campaignId, CollaborationStatus status, Pageable pageable) {
    Permissions.require(Permission.MANAGE_OWN_COLLABORATION);
    Campaign campaign =
        campaigns.findById(campaignId).orElseThrow(() -> new NotFoundException("Campaign not found"));
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
                  boolean hasOutcome = outcomes.existsByCollaborationId(c.getId());
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
                      hasReview);
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
    User actor = Permissions.actor();

    boolean isBrandOwner =
        c.getCampaign().getBrandProfile().getUser().getId().equals(actor.getId());
    boolean isCreatorOwner =
        (c.getMaster() != null && c.getMaster().getUser().getId().equals(actor.getId()))
            || (c.getCreator().getMaster() != null
                && c.getCreator().getMaster().getUser().getId().equals(actor.getId()));
    boolean isStaff = actor.getRole() == Role.ADMIN || actor.getRole() == Role.DATA_MANAGER;

    if (!isBrandOwner && !isCreatorOwner && !isStaff) {
      throw new ForbiddenException("Access denied to collaboration");
    }

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
                        o.getResultNote(),
                        o.getEvidenceQuality(),
                        o.getRecordedAt(),
                        o.getCompletedAt()))
            .orElse(null);

    CreatorReviewResponse reviewDto =
        reviews
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

    List<EvidenceAssetResponse> evidenceList =
        evidenceAssets.findByCollaborationIdOrderByUploadedAtDesc(collaborationId).stream()
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
        c.getNote(),
        c.getStartAt(),
        c.getCompletedAt(),
        c.getUpdatedAt(),
        outcomeDto,
        reviewDto,
        evidenceList);
  }

  @Transactional
  public EvidenceAssetResponse submitEvidence(UUID collaborationId, SubmitEvidenceRequest request) {
    validate(request);
    Collaboration collaboration =
        collaborations
            .findById(collaborationId)
            .orElseThrow(() -> new NotFoundException("Collaboration not found"));

    User actor = Permissions.actor();
    boolean isBrandOwner =
        collaboration.getCampaign().getBrandProfile().getUser().getId().equals(actor.getId());
    boolean isCreatorOwner =
        (collaboration.getMaster() != null
                && collaboration.getMaster().getUser().getId().equals(actor.getId()))
            || (collaboration.getCreator().getMaster() != null
                && collaboration.getCreator().getMaster().getUser().getId().equals(actor.getId()));
    boolean isStaff = actor.getRole() == Role.ADMIN || actor.getRole() == Role.DATA_MANAGER;

    if (!isBrandOwner && !isCreatorOwner && !isStaff) {
      throw new ForbiddenException("Access denied to submit evidence for this collaboration");
    }

    String source = isCreatorOwner ? "CREATOR_SUBMITTED" : "BRAND_UPLOAD";
    LocalDateTime now = LocalDateTime.now();

    if (request.publishedContentUrl() != null && !request.publishedContentUrl().isBlank()) {
      collaboration.setPublishedContentUrl(request.publishedContentUrl());
      collaboration.setUpdatedAt(now);
    } else if ("POST_LINK".equalsIgnoreCase(request.assetType())
        && (collaboration.getPublishedContentUrl() == null
            || collaboration.getPublishedContentUrl().isBlank())) {
      collaboration.setPublishedContentUrl(request.storageUrl());
      collaboration.setUpdatedAt(now);
    }

    CampaignOutcome outcome = outcomes.findByCollaborationId(collaborationId).orElse(null);

    EvidenceAsset asset =
        evidenceAssets.save(
            EvidenceAsset.builder()
                .collaboration(collaboration)
                .outcome(outcome)
                .assetType(request.assetType())
                .storageUrl(request.storageUrl())
                .source(source)
                .uploadedAt(now)
                .build());

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

    User actor = Permissions.actor();
    boolean isBrandOwner =
        collaboration.getCampaign().getBrandProfile().getUser().getId().equals(actor.getId());
    boolean isCreatorOwner =
        (collaboration.getMaster() != null
                && collaboration.getMaster().getUser().getId().equals(actor.getId()))
            || (collaboration.getCreator().getMaster() != null
                && collaboration.getCreator().getMaster().getUser().getId().equals(actor.getId()));
    boolean isStaff = actor.getRole() == Role.ADMIN || actor.getRole() == Role.DATA_MANAGER;

    if (!isBrandOwner && !isCreatorOwner && !isStaff) {
      throw new ForbiddenException("Access denied to view evidence for this collaboration");
    }

    return evidenceAssets.findByCollaborationIdOrderByUploadedAtDesc(collaborationId).stream()
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
  @PreAuthorize("hasRole('CREATOR')")
  public List<CreatorCollaborationDto> getCreatorPortalCollaborations(CollaborationStatus status) {
    User actor = Permissions.actor();
    if (actor.getRole() != Role.CREATOR) {
      throw new ForbiddenException("Only creators can access creator portal collaborations");
    }

    CreatorMaster master = creatorMasters.findByUserId(actor.getId()).orElse(null);
    if (master == null) {
      return List.of();
    }

    List<Collaboration> list =
        (status == null)
            ? collaborations.findByMasterId(master.getId())
            : collaborations.findByMasterIdAndStatus(master.getId(), status);

    return list.stream()
        .map(
            c -> {
              boolean hasOutcome = outcomes.existsByCollaborationId(c.getId());
              boolean hasReview = reviews.existsByCollaborationId(c.getId());
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
                  c.getNote(),
                  c.getStartAt(),
                  c.getCompletedAt(),
                  c.getUpdatedAt(),
                  hasOutcome,
                  hasReview);
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
