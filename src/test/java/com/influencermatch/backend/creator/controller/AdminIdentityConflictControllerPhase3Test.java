package com.influencermatch.backend.creator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.dto.ConflictDetailDto;
import com.influencermatch.backend.creator.dto.ConflictResolutionDecision;
import com.influencermatch.backend.creator.dto.ResolveConflictRequest;
import com.influencermatch.backend.creator.service.CreatorIdentityConflictService;
import com.influencermatch.backend.exception.ForbiddenException;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminIdentityConflictControllerPhase3Test {

  private CreatorIdentityConflictService conflictService;
  private AdminIdentityConflictController controller;

  private User dataManagerUser;

  @BeforeEach
  void setUp() {
    conflictService = mock(CreatorIdentityConflictService.class);
    controller = new AdminIdentityConflictController(conflictService);

    dataManagerUser = User.builder()
        .id(UUID.randomUUID())
        .email("datamanager@influencermatch.com")
        .fullName("Data Manager")
        .role(Role.ADMIN)
        .status(UserStatus.ACTIVE)
        .build();

    // Authenticate with RESOLVE_CREATOR_DUPLICATE permission
    var authorities = List.of(
        new SimpleGrantedAuthority("ROLE_ADMIN"),
        new SimpleGrantedAuthority("RESOLVE_CREATOR_DUPLICATE"),
        new SimpleGrantedAuthority("REVIEW_CREATOR_CLAIM"));

    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(dataManagerUser, null, authorities));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("POST /admin/creator-conflicts/{id}/resolve succeeds with APPROVE_TRANSFER")
  void resolveConflictApproveTransferSucceeds() {
    UUID conflictId = UUID.randomUUID();
    ResolveConflictRequest request = ResolveConflictRequest.builder()
        .decision(ConflictResolutionDecision.APPROVE_TRANSFER)
        .notes("Verified official contract with claimant creator")
        .build();

    ConflictDetailDto expectedDto = ConflictDetailDto.builder()
        .id(conflictId)
        .conflictType("CREATOR_ALREADY_LINKED")
        .status("RESOLVED")
        .resolutionNotes("Verified official contract with claimant creator")
        .resolvedByUserId(dataManagerUser.getId())
        .resolvedAt(LocalDateTime.now())
        .build();

    when(conflictService.resolveConflict(eq(conflictId), eq(ConflictResolutionDecision.APPROVE_TRANSFER), eq(request.getNotes()), any(User.class)))
        .thenReturn(expectedDto);

    ApiResponse<ConflictDetailDto> response = controller.resolveConflict(conflictId, request);

    verify(conflictService).resolveConflict(eq(conflictId), eq(ConflictResolutionDecision.APPROVE_TRANSFER), eq(request.getNotes()), eq(dataManagerUser));

    assertThat(response).isNotNull();
    assertThat(response.success()).isTrue();
    assertThat(response.data().getStatus()).isEqualTo("RESOLVED");
    assertThat(response.data().getResolutionNotes()).isEqualTo("Verified official contract with claimant creator");
  }

  @Test
  @DisplayName("POST /admin/creator-conflicts/{id}/resolve succeeds with REJECT")
  void resolveConflictRejectSucceeds() {
    UUID conflictId = UUID.randomUUID();
    ResolveConflictRequest request = ResolveConflictRequest.builder()
        .decision(ConflictResolutionDecision.REJECT)
        .notes("Insufficient evidence of channel transfer")
        .build();

    ConflictDetailDto expectedDto = ConflictDetailDto.builder()
        .id(conflictId)
        .conflictType("CREATOR_ALREADY_LINKED")
        .status("REJECTED")
        .resolutionNotes("Insufficient evidence of channel transfer")
        .resolvedByUserId(dataManagerUser.getId())
        .resolvedAt(LocalDateTime.now())
        .build();

    when(conflictService.resolveConflict(eq(conflictId), eq(ConflictResolutionDecision.REJECT), eq(request.getNotes()), any(User.class)))
        .thenReturn(expectedDto);

    ApiResponse<ConflictDetailDto> response = controller.resolveConflict(conflictId, request);

    assertThat(response.data().getStatus()).isEqualTo("REJECTED");
  }

  @Test
  @DisplayName("GET /admin/creator-conflicts returns paginated conflict list")
  void listConflictsReturnsPage() {
    UUID conflictId = UUID.randomUUID();
    ConflictDetailDto dto = ConflictDetailDto.builder()
        .id(conflictId)
        .status("PENDING_MANAGER")
        .conflictType("CREATOR_ALREADY_LINKED")
        .build();

    when(conflictService.listConflicts(eq("PENDING_MANAGER"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(dto)));

    var response = controller.listConflicts("PENDING_MANAGER", 0, 10);

    assertThat(response.success()).isTrue();
    assertThat(response.data().items()).hasSize(1);
    assertThat(response.data().items().get(0).getStatus()).isEqualTo("PENDING_MANAGER");
  }

  @Test
  @DisplayName("POST /admin/creator-conflicts/{id}/resolve rejects unauthorized user lacking permission")
  void resolveConflictRejectsUnauthorizedUser() {
    // Authenticate a Brand user lacking RESOLVE_CREATOR_DUPLICATE
    User brandUser = User.builder()
        .id(UUID.randomUUID())
        .email("brand@influencermatch.com")
        .role(Role.BRAND)
        .status(UserStatus.ACTIVE)
        .build();

    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(brandUser, null, List.of(new SimpleGrantedAuthority("ROLE_BRAND"))));

    UUID conflictId = UUID.randomUUID();
    ResolveConflictRequest request = ResolveConflictRequest.builder()
        .decision(ConflictResolutionDecision.APPROVE_TRANSFER)
        .build();

    assertThatThrownBy(() -> controller.resolveConflict(conflictId, request))
        .isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(conflictService);
  }
}
