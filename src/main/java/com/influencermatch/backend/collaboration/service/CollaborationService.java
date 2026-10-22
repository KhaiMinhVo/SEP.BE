package com.influencermatch.backend.collaboration.service;

import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.collaboration.dto.*;
import com.influencermatch.backend.collaboration.enums.*;
import com.influencermatch.backend.collaboration.model.*;
import com.influencermatch.backend.collaboration.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.security.permission.*;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CollaborationService {
  private final CampaignRepository campaigns;
  private final BrandCreatorRelationshipRepository relationships;
  private final CollaborationRepository collaborations;
  private final CampaignOutcomeRepository outcomes;
  private final CreatorReviewRepository reviews;
  private final EntityManager entityManager;
  private final Validator validator;

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
    if (relationship.getStage() != RelationshipStage.CONTACTED
        && relationship.getStage() != RelationshipStage.INTERESTED)
      throw conflict(
          ErrorCode.INVALID_COLLABORATION_STATE, "Relationship must be CONTACTED or INTERESTED");
    try {
      Currency.getInstance(request.currency());
    } catch (IllegalArgumentException ex) {
      throw new ValidationException("currency must be a valid ISO currency code");
    }
    LocalDateTime now = LocalDateTime.now();
    Collaboration collaboration =
        collaborations.save(
            Collaboration.builder()
                .campaign(campaign)
                .relationship(relationship)
                .creator(relationship.getCreator())
                .agreedFee(request.agreedFee())
                .currency(request.currency())
                .note(request.note())
                .status(CollaborationStatus.IN_PROGRESS)
                .paymentStatus(CollaborationPaymentStatus.PENDING)
                .startAt(now)
                .updatedAt(now)
                .build());
    relationship.setStage(RelationshipStage.COLLABORATING);
    relationship.setAgreedFee(request.agreedFee());
    relationship.setCurrency(request.currency());
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
    LocalDateTime now = LocalDateTime.now();
    CampaignOutcome outcome =
        outcomes.save(
            CampaignOutcome.builder()
                .collaboration(collaboration)
                .views(request.views())
                .clicks(request.clicks())
                .resultNote(request.resultNote())
                .evidenceQuality("SELF_REPORTED")
                .recordedAt(now)
                .completedAt(now)
                .build());
    collaboration.setPublishedContentUrl(request.publishedContentUrl());
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
