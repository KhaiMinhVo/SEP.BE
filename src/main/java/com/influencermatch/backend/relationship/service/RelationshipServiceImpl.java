package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.relationship.dto.CreateRelationshipRequest;
import com.influencermatch.backend.relationship.dto.RelationshipDto;
import com.influencermatch.backend.relationship.dto.UpdateRelationshipDetailsRequest;
import com.influencermatch.backend.relationship.dto.UpdateRelationshipStageRequest;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RelationshipServiceImpl implements RelationshipService {

  private final BrandCreatorRelationshipRepository relationshipRepository;
  private final ShortlistItemRepository shortlistItemRepository;
  private final CampaignOwnershipService ownership;
  private final CreatorRepository creatorRepository;
  private final UserRepository userRepository;

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.campaign.service.CampaignWriteLock writeLock;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.jdbc.core.JdbcTemplate jdbc;

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.audit.service.AuditService audit;

  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

  @Override
  @Transactional
  public RelationshipDto createRelationship(UUID campaignId, CreateRelationshipRequest request) {
    Permissions.require(Permission.MANAGE_OWN_RELATIONSHIP);
    ownership.requireOwned(campaignId);
    Campaign campaign = writeLock.owned(campaignId);
    UUID userId = Permissions.actor().getId();
    var existing =
        relationshipRepository.findByCampaignIdAndCreatorId(campaignId, request.getCreatorId());
    if (existing.isPresent()) return mapToDto(existing.get());
    validateFees(
        request.getOfferedFee(),
        request.getCounterofferFee(),
        request.getQuotedFee(),
        null,
        request.getCurrency());

    Creator creator =
        creatorRepository
            .findById(request.getCreatorId())
            .orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Creator not found"));

    User user =
        userRepository
            .findById(userId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));

    ShortlistItem shortlistItem = null;
    if (request.getShortlistItemId() != null) {
      shortlistItem =
          shortlistItemRepository
              .findByIdAndCampaignIdAndUserId(request.getShortlistItemId(), campaignId, userId)
              .orElseThrow(
                  () ->
                      new BusinessException(
                          ErrorCode.RESOURCE_NOT_FOUND, "Shortlist item not found"));
    }

    if (shortlistItem != null && !shortlistItem.getCreator().getId().equals(creator.getId()))
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "Shortlist item belongs to another Creator");

    BrandCreatorRelationship relationship =
        BrandCreatorRelationship.builder()
            .campaign(campaign)
            .creator(creator)
            .user(user)
            .shortlistItem(shortlistItem)
            .stage(RelationshipStage.CONTACTED) // Default starting stage
            .contactMethod(request.getContactMethod())
            .offeredFee(request.getOfferedFee())
            .master(creator.getMaster())
            .currency(request.getCurrency() == null ? "VND" : request.getCurrency())
            .notes(request.getNotes())
            .lastContactAt(LocalDateTime.now())
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    relationship = relationshipRepository.saveAndFlush(relationship);
    audit.record(
        "RELATIONSHIP_CREATED",
        "RELATIONSHIP",
        relationship.getId(),
        null,
        java.util.Map.of("creatorId", creator.getId()),
        "CRM contact created");
    return mapToDto(relationship);
  }

  @Override
  @Transactional
  public RelationshipDto updateStage(
      UUID campaignId, UUID relationshipId, UpdateRelationshipStageRequest request) {
    Permissions.require(Permission.MANAGE_OWN_RELATIONSHIP);
    ownership.requireOwned(campaignId);
    writeLock.owned(campaignId);
    BrandCreatorRelationship relationship = getEntity(campaignId, relationshipId);
    relationship = relationshipRepository.findLockedById(relationshipId).orElseThrow();
    entityManager.refresh(relationship);
    if (!jdbc.queryForList(
            "select deal_id from cooperation_member where relationship_id=? and active",
            relationshipId)
        .isEmpty())
      throw new BusinessException(
          ErrorCode.DEAL_STATE_CONFLICT, "Use the Deal workflow for grouped relationships");
    if (request.getAgreedFee() != null)
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "Agreed fee is written only by deal confirmation");
    var before = relationship.getStage();
    var after = request.getStage();
    boolean valid =
        before == after
            || (before == RelationshipStage.SHORTLISTED && after == RelationshipStage.CONTACTED)
            || (before == RelationshipStage.CONTACTED
                && java.util.Set.of(
                        RelationshipStage.NEGOTIATING,
                        RelationshipStage.DECLINED,
                        RelationshipStage.CANCELLED)
                    .contains(after))
            || (before == RelationshipStage.NEGOTIATING
                && java.util.Set.of(RelationshipStage.DECLINED, RelationshipStage.CANCELLED)
                    .contains(after));
    if (!valid)
      throw new BusinessException(
          ErrorCode.DEAL_STATE_CONFLICT, "Invalid CRM transition; use confirm for CONFIRMED");
    if (before == after && request.getNextAction() == null) return mapToDto(relationship);
    relationship.setStage(after);
    audit.record(
        "RELATIONSHIP_STAGE_CHANGED",
        "RELATIONSHIP",
        relationshipId,
        java.util.Map.of("stage", before),
        java.util.Map.of("stage", after),
        "CRM transition");
    if (request.getNextAction() != null) {
      relationship.setNextAction(request.getNextAction());
    }
    relationship.setUpdatedAt(LocalDateTime.now());
    return mapToDto(relationshipRepository.save(relationship));
  }

  @Override
  @Transactional
  public RelationshipDto updateDetails(
      UUID campaignId, UUID relationshipId, UpdateRelationshipDetailsRequest request) {
    Permissions.require(Permission.MANAGE_OWN_RELATIONSHIP);
    BrandCreatorRelationship relationship = getEntity(campaignId, relationshipId);

    ownership.requireOwned(campaignId);
    writeLock.owned(campaignId);
    relationship = relationshipRepository.findLockedById(relationshipId).orElseThrow();
    entityManager.refresh(relationship);
    validateFees(
        request.getOfferedFee(),
        request.getCounterofferFee(),
        request.getQuotedFee(),
        request.getAgreedFee(),
        request.getCurrency());
    if (request.getOfferedFee() != null) {
      if (!jdbc.queryForList(
              "select deal_id from cooperation_member where relationship_id=? and active",
              relationshipId)
          .isEmpty())
        throw new BusinessException(
            ErrorCode.DEAL_STATE_CONFLICT, "Create a proposal to change deal terms");
      relationship.setOfferedFee(request.getOfferedFee());
    }
    if (request.getContactMethod() != null)
      relationship.setContactMethod(request.getContactMethod());
    if (request.getCurrency() != null) relationship.setCurrency(request.getCurrency());
    if (request.getNotes() != null) relationship.setNotes(request.getNotes());
    if (request.getNextAction() != null) relationship.setNextAction(request.getNextAction());

    audit.record(
        "RELATIONSHIP_DETAILS_UPDATED",
        "RELATIONSHIP",
        relationshipId,
        null,
        java.util.Map.of("stage", relationship.getStage()),
        "CRM details updated");
    relationship.setUpdatedAt(LocalDateTime.now());
    return mapToDto(relationshipRepository.save(relationship));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<RelationshipDto> getRelationshipsByCampaign(UUID campaignId, Pageable pageable) {
    Permissions.require(Permission.MANAGE_OWN_RELATIONSHIP);
    ownership.requireOwned(campaignId);
    return relationshipRepository
        .findByCampaignIdAndUserId(campaignId, Permissions.actor().getId(), pageable)
        .map(this::mapToDto);
  }

  @Override
  @Transactional(readOnly = true)
  public RelationshipDto getRelationship(UUID campaignId, UUID relationshipId) {
    Permissions.require(Permission.MANAGE_OWN_RELATIONSHIP);
    return mapToDto(getEntity(campaignId, relationshipId));
  }

  private BrandCreatorRelationship getEntity(UUID campaignId, UUID id) {
    ownership.requireOwned(campaignId);
    return relationshipRepository
        .findByIdAndCampaignIdAndUserId(id, campaignId, Permissions.actor().getId())
        .orElseThrow(
            () ->
                new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "Relationship not found or access denied"));
  }

  private RelationshipDto mapToDto(BrandCreatorRelationship entity) {
    var links =
        jdbc.queryForList(
            "select d.id,d.status,d.collaboration_id from cooperation_member m join"
                + " cooperation_deal d on d.id=m.deal_id where m.relationship_id=? order by"
                + " m.active desc,d.updated_at desc,d.id desc limit 1",
            entity.getId());
    var link =
        links.isEmpty() ? java.util.Collections.<String, Object>emptyMap() : links.getFirst();
    return RelationshipDto.builder()
        .masterId(
            entity.getCreator().getMaster() == null
                ? null
                : entity.getCreator().getMaster().getId())
        .dealId((UUID) link.get("id"))
        .dealStatus((String) link.get("status"))
        .collaborationId((UUID) link.get("collaboration_id"))
        .id(entity.getId())
        .campaignId(entity.getCampaign().getId())
        .creatorId(entity.getCreator().getId())
        .creatorName(entity.getCreator().getDisplayName())
        .shortlistItemId(
            entity.getShortlistItem() != null ? entity.getShortlistItem().getId() : null)
        .stage(entity.getStage())
        .contactMethod(entity.getContactMethod())
        .quotedFee(entity.getQuotedFee())
        .offeredFee(entity.getOfferedFee())
        .counterofferFee(entity.getCounterofferFee())
        .agreedFee(entity.getAgreedFee())
        .currency(entity.getCurrency())
        .lastContactAt(entity.getLastContactAt())
        .notes(entity.getNotes())
        .nextAction(entity.getNextAction())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }

  private void validateFees(
      java.math.BigDecimal offer,
      java.math.BigDecimal counter,
      java.math.BigDecimal quote,
      java.math.BigDecimal agreed,
      String currency) {
    if (counter != null || quote != null || agreed != null)
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR,
          "Use offers/proposals and confirm; legacy quotedFee and agreedFee cannot be directly"
              + " written");
    if (offer != null
        && (offer.signum() <= 0 || offer.scale() > 2 || offer.precision() - offer.scale() > 17))
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "offeredFee must be positive");
    if (currency != null && !"VND".equals(currency))
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Only VND is supported");
  }
}
