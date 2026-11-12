package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.dto.ConflictDetailDto;
import com.influencermatch.backend.creator.dto.ConflictResolutionDecision;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.user.model.User;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorIdentityConflictService {

  private final CreatorIdentityConflictRepository conflictRepository;
  private final CreatorRepository creatorRepository;
  private final CreatorIdentityAuditRepository auditRepository;
  private final ShortlistItemRepository shortlistItemRepository;
  private final BrandCreatorRelationshipRepository relationshipRepository;

  @Transactional(readOnly = true)
  public Page<ConflictDetailDto> listConflicts(String status, Pageable pageable) {
    Page<CreatorIdentityConflict> page = (status != null && !status.isBlank())
        ? conflictRepository.findByStatus(status.toUpperCase(), pageable)
        : conflictRepository.findAll(pageable);

    return page.map(this::toDto);
  }

  @Transactional
  public ConflictDetailDto resolveConflict(
      UUID conflictId, ConflictResolutionDecision decision, String notes, User resolver) {

    throw new ConflictException(ErrorCode.CREATOR_ALREADY_LINKED, "Ownership transfer is outside MVP");
  }

  private ConflictDetailDto toDto(CreatorIdentityConflict c) {
    Creator acc = c.getSourceAccount();
    CreatorMaster master = c.getTargetMaster();

    return ConflictDetailDto.builder()
        .id(c.getId())
        .conflictType(c.getConflictType())
        .sourceAccountId(acc != null ? acc.getId() : null)
        .sourceAccountPlatform(acc != null ? acc.getPlatform() : null)
        .sourceAccountUsername(acc != null ? acc.getUserName() : null)
        .sourceAccountExternalId(acc != null ? acc.getExternalId() : null)
        .currentMasterId(acc != null && acc.getMaster() != null ? acc.getMaster().getId() : null)
        .targetMasterId(master != null ? master.getId() : null)
        .targetMasterName(master != null ? master.getCanonicalName() : null)
        .conflictDetails(c.getConflictDetails())
        .status(c.getStatus())
        .resolutionNotes(c.getResolutionNotes())
        .resolvedByUserId(c.getResolvedBy() != null ? c.getResolvedBy().getId() : null)
        .resolvedAt(c.getResolvedAt())
        .createdAt(c.getCreatedAt())
        .build();
  }
}
