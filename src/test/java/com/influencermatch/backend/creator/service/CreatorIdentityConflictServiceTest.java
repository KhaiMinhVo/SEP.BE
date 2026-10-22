package com.influencermatch.backend.creator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.creator.dto.ConflictDetailDto;
import com.influencermatch.backend.creator.dto.ConflictResolutionDecision;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class CreatorIdentityConflictServiceTest {

  private CreatorIdentityConflictRepository conflictRepository;
  private CreatorRepository creatorRepository;
  private CreatorIdentityAuditRepository auditRepository;
  private ShortlistItemRepository shortlistItemRepository;
  private BrandCreatorRelationshipRepository relationshipRepository;

  private CreatorIdentityConflictService conflictService;

  private User mockManager;
  private CreatorMaster originalMaster;
  private CreatorMaster claimantMaster;
  private Creator disputedAccount;
  private CreatorIdentityConflict pendingConflict;

  @BeforeEach
  void setUp() {
    conflictRepository = mock(CreatorIdentityConflictRepository.class);
    creatorRepository = mock(CreatorRepository.class);
    auditRepository = mock(CreatorIdentityAuditRepository.class);
    shortlistItemRepository = mock(ShortlistItemRepository.class);
    relationshipRepository = mock(BrandCreatorRelationshipRepository.class);

    conflictService = new CreatorIdentityConflictService(
        conflictRepository,
        creatorRepository,
        auditRepository,
        shortlistItemRepository,
        relationshipRepository
    );

    mockManager = User.builder()
        .id(UUID.randomUUID())
        .email("admin@influencermatch.com")
        .fullName("Data Manager")
        .role(Role.ADMIN)
        .status(UserStatus.ACTIVE)
        .build();

    originalMaster = CreatorMaster.builder()
        .id(UUID.randomUUID())
        .canonicalName("Original Owner Master")
        .build();

    claimantMaster = CreatorMaster.builder()
        .id(UUID.randomUUID())
        .canonicalName("Claimant Master")
        .build();

    disputedAccount = Creator.builder()
        .id(UUID.randomUUID())
        .platform("TIKTOK")
        .externalId("tt_disputed_123")
        .userName("disputed_user")
        .master(originalMaster)
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    pendingConflict = CreatorIdentityConflict.builder()
        .id(UUID.randomUUID())
        .conflictType("ACCOUNT_ALREADY_LINKED")
        .sourceAccount(disputedAccount)
        .targetMaster(claimantMaster)
        .conflictDetails(Map.of("reason", "Legitimate owner transfer request"))
        .status("PENDING_MANAGER")
        .createdAt(LocalDateTime.now().minusDays(1))
        .build();
  }

  @Test
  void listConflicts_returnsPage() {
    when(conflictRepository.findByStatus(eq("PENDING_MANAGER"), any()))
        .thenReturn(new PageImpl<>(List.of(pendingConflict)));

    Page<ConflictDetailDto> page = conflictService.listConflicts("PENDING_MANAGER", PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).getId()).isEqualTo(pendingConflict.getId());
    assertThat(page.getContent().get(0).getTargetMasterName()).isEqualTo("Claimant Master");
  }

  @Test
  void resolveConflict_approveTransfer_updatesMasterAndDualRefs() {
    when(conflictRepository.findById(pendingConflict.getId()))
        .thenReturn(Optional.of(pendingConflict));
    when(conflictRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    ConflictDetailDto result = conflictService.resolveConflict(
        pendingConflict.getId(),
        ConflictResolutionDecision.APPROVE_TRANSFER,
        "Ownership verified via identity documents",
        mockManager
    );

    assertThat(result.getStatus()).isEqualTo("RESOLVED");
    assertThat(pendingConflict.getStatus()).isEqualTo("RESOLVED");
    assertThat(pendingConflict.getResolvedBy()).isEqualTo(mockManager);
    assertThat(pendingConflict.getResolutionNotes()).isEqualTo("Ownership verified via identity documents");

    // Transferred to claimant master
    assertThat(disputedAccount.getMaster()).isEqualTo(claimantMaster);
    verify(creatorRepository).save(disputedAccount);

    // Dual references updated
    verify(shortlistItemRepository).updateMasterForCreator(disputedAccount.getId(), claimantMaster);
    verify(relationshipRepository).updateMasterForCreator(disputedAccount.getId(), claimantMaster);

    verify(auditRepository).save(any());
  }

  @Test
  void resolveConflict_reject_keepsCurrentMaster() {
    when(conflictRepository.findById(pendingConflict.getId()))
        .thenReturn(Optional.of(pendingConflict));
    when(conflictRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    ConflictDetailDto result = conflictService.resolveConflict(
        pendingConflict.getId(),
        ConflictResolutionDecision.REJECT,
        "Insufficient proof provided",
        mockManager
    );

    assertThat(result.getStatus()).isEqualTo("REJECTED");
    assertThat(pendingConflict.getStatus()).isEqualTo("REJECTED");

    // Master remains original master
    assertThat(disputedAccount.getMaster()).isEqualTo(originalMaster);
    verify(creatorRepository, never()).save(any());
    verify(shortlistItemRepository, never()).updateMasterForCreator(any(), any());

    verify(auditRepository).save(any());
  }

  @Test
  void resolveConflict_alreadyResolved_throwsConflict() {
    pendingConflict.setStatus("RESOLVED");
    when(conflictRepository.findById(pendingConflict.getId()))
        .thenReturn(Optional.of(pendingConflict));

    assertThatThrownBy(() -> conflictService.resolveConflict(
        pendingConflict.getId(),
        ConflictResolutionDecision.APPROVE_TRANSFER,
        "Trying again",
        mockManager
    )).isInstanceOf(ConflictException.class);
  }
}
