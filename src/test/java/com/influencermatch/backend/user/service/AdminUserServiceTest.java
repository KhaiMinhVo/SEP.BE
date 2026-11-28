package com.influencermatch.backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.auth.dto.AdminUserResponse;
import com.influencermatch.backend.auth.dto.UpdateUserStatusRequest;
import com.influencermatch.backend.auth.service.RefreshTokenService;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ForbiddenException;
import com.influencermatch.backend.exception.NotFoundException;
import com.influencermatch.backend.user.dto.UpdateUserRoleRequest;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository users;
    
    @Mock
    private RefreshTokenService refreshTokens;
    
    @Mock
    private AuditService audit;
    
    @Mock
    private BrandProfileRepository profiles;

    @Mock
    private com.influencermatch.backend.creator.repository.CreatorRepository creators;

    @Mock
    private com.influencermatch.backend.creator.repository.CreatorClaimRepository claims;

    @Mock private com.influencermatch.backend.user.service.AdminAccessGuard guard;

    @InjectMocks
    private AdminUserService adminUserService;

    private User adminPrincipal;

    @BeforeEach
    void setUp() {
        adminPrincipal = User.builder()
                .id(UUID.randomUUID())
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        setSecurityContext(adminPrincipal);
        lenient().when(guard.lock(any(),any())).thenAnswer(i->users.findById(i.getArgument(0)).orElseThrow());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(User user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    @Test
    @DisplayName("list - Success")
    void list_Success() {
        Page<User> usersPage = new PageImpl<>(List.of(User.builder().id(UUID.randomUUID()).role(Role.BRAND).build()));
        when(users.findAll(any(org.springframework.data.jpa.domain.Specification.class),any(org.springframework.data.domain.Pageable.class))).thenReturn(usersPage);
        
        Page<AdminUserResponse> result = adminUserService.list(PageRequest.of(0, 10));
        
        assertThat(result).hasSize(1);
        verify(users).findAll(any(org.springframework.data.jpa.domain.Specification.class),any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    @DisplayName("list - Throws Exception if Not Admin")
    void list_ThrowsExceptionIfNotAdmin() {
        // Mock non-admin role which typically doesn't have VIEW_USER
        adminPrincipal.setRole(Role.BRAND);
        setSecurityContext(adminPrincipal);
        
        assertThatThrownBy(() -> adminUserService.list(PageRequest.of(0, 10)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("get - Success")
    void get_Success() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).role(Role.DATA_MANAGER).build();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        
        AdminUserResponse response = adminUserService.get(userId);
        
        assertThat(response.id()).isEqualTo(userId);
    }

    @Test
    @DisplayName("get - User Not Found")
    void get_UserNotFound() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.empty());
        
        assertThatThrownBy(() -> adminUserService.get(userId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("changeStatus - Success")
    void changeStatus_Success() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.BRAND).status(UserStatus.ACTIVE).authVersion(1L).build();
        UpdateUserStatusRequest request = new UpdateUserStatusRequest(UserStatus.LOCKED, "Violated terms");
        
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        
        AdminUserResponse response = adminUserService.changeStatus(userId, request);
        
        assertThat(response.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(targetUser.getAuthVersion()).isEqualTo(2L);
        verify(guard).lock(eq(userId),any());
        verify(refreshTokens).revokeAll(userId);
        verify(audit).recordAs(eq(adminPrincipal.getId()),eq("CHANGE_USER_STATUS"), eq("User"), eq(userId), anyMap(), anyMap(), eq("Violated terms"));
    }

    @Test
    @DisplayName("changeUserStatus UTCID05 [N]: Same status returns current response without side effects")
    void changeStatus_SameStatus() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.BRAND).status(UserStatus.ACTIVE).build();
        UpdateUserStatusRequest request = new UpdateUserStatusRequest(UserStatus.ACTIVE, "Re-enable");
        
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        
        AdminUserResponse response = adminUserService.changeStatus(userId, request);
        
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        verify(users, never()).save(any());
        verifyNoInteractions(audit,refreshTokens);
    }

    @Test
    @DisplayName("changeStatus - Protect Last Admin")
    void changeStatus_ProtectLastAdmin() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.ADMIN).status(UserStatus.ACTIVE).build();
        UpdateUserStatusRequest request = new UpdateUserStatusRequest(UserStatus.LOCKED, "Lock");
        
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        when(users.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).thenReturn(1L);
        
        assertThatThrownBy(() -> adminUserService.changeStatus(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("At least one active administrator is required");
    }

    @Test
    @DisplayName("changeUserRole UTCID01 [N]: Valid role change increments auth version, revokes tokens, and audits")
    void changeRole_Success() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.DATA_MANAGER).status(UserStatus.ACTIVE).authVersion(1L).build();
        UpdateUserRoleRequest request = new UpdateUserRoleRequest(Role.ADMIN, "Promoted");
        
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        
        AdminUserResponse response = adminUserService.changeRole(userId, request);
        
        assertThat(response.role()).isEqualTo(Role.ADMIN);
        assertThat(targetUser.getAuthVersion()).isEqualTo(2L);
        verify(guard).lock(eq(userId),any());
        verify(refreshTokens).revokeAll(userId);
        verify(audit).recordAs(eq(adminPrincipal.getId()),eq("ASSIGN_USER_ROLE"), eq("User"), eq(userId), anyMap(), anyMap(), eq("Promoted"));
    }

    @Test
    @DisplayName("changeUserRole UTCID04 [A]: Brand with profile cannot change to a non-BRAND role")
    void changeRole_PreventChangingBrandWithProfile() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.BRAND).status(UserStatus.ACTIVE).build();
        UpdateUserRoleRequest request = new UpdateUserRoleRequest(Role.DATA_MANAGER, "Demoted");
        
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        when(profiles.existsByUserId(userId)).thenReturn(true);
        
        assertThatThrownBy(() -> adminUserService.changeRole(userId, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(
                                com.influencermatch.backend.exception.ErrorCode.BRAND_ROLE_CHANGE_CONFLICT));
    }

    @Test
    @DisplayName("changeUserStatus UTCID01 [N]: Active non-admin to DISABLED increments authVersion, revokes tokens, records audit")
    void changeStatus_activeNonAdminToDisabled() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.BRAND).status(UserStatus.ACTIVE).authVersion(3L).build();
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));

        AdminUserResponse response = adminUserService.changeStatus(
                userId, new UpdateUserStatusRequest(UserStatus.DISABLED, "Account deactivated"));

        assertThat(response.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(targetUser.getAuthVersion()).isEqualTo(4L);
        verify(refreshTokens).revokeAll(userId);
        verify(audit).recordAs(eq(adminPrincipal.getId()), eq("CHANGE_USER_STATUS"), eq("User"), eq(userId),
                anyMap(), anyMap(), eq("Account deactivated"));
    }

    @Test
    @DisplayName("changeUserStatus UTCID02 [B]: Reason with exact 500 characters succeeds")
    void changeStatus_reason500CharsSucceeds() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.BRAND).status(UserStatus.ACTIVE).authVersion(1L).build();
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        String reason500 = "r".repeat(500);

        AdminUserResponse response = adminUserService.changeStatus(
                userId, new UpdateUserStatusRequest(UserStatus.LOCKED, reason500));

        assertThat(response.status()).isEqualTo(UserStatus.LOCKED);
        verify(audit).recordAs(eq(adminPrincipal.getId()), eq("CHANGE_USER_STATUS"), eq("User"), eq(userId),
                anyMap(), anyMap(), eq(reason500));
    }

    @Test
    @DisplayName("changeUserStatus UTCID03 [A]: Reason 501 characters throws ValidationException")
    void changeStatus_reason501CharsThrowsValidation() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> adminUserService.changeStatus(
                userId, new UpdateUserStatusRequest(UserStatus.LOCKED, "r".repeat(501))))
                .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
        verifyNoInteractions(refreshTokens, audit);
    }

    @Test
    @DisplayName("changeUserStatus UTCID04 [A]: Last active admin to DISABLED throws LAST_ACTIVE_ADMIN")
    void changeStatus_lastActiveAdminToDisabledThrows() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.ADMIN).status(UserStatus.ACTIVE).build();
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        when(users.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> adminUserService.changeStatus(
                userId, new UpdateUserStatusRequest(UserStatus.DISABLED, "Retire")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(com.influencermatch.backend.exception.ErrorCode.LAST_ACTIVE_ADMIN));
        assertThat(targetUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("changeUserRole UTCID02 [B]: Reason with exact 1 character succeeds")
    void changeRole_reasonOneCharSucceeds() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.DATA_MANAGER).status(UserStatus.ACTIVE).authVersion(1L).build();
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));

        AdminUserResponse response = adminUserService.changeRole(
                userId, new UpdateUserRoleRequest(Role.BRAND, "x"));

        assertThat(response.role()).isEqualTo(Role.BRAND);
        verify(audit).recordAs(eq(adminPrincipal.getId()), eq("ASSIGN_USER_ROLE"), eq("User"), eq(userId),
                anyMap(), anyMap(), eq("x"));
    }

    @Test
    @DisplayName("changeUserRole UTCID03 [A]: Creator with pending claim throws CREATOR_ROLE_CHANGE_CONFLICT")
    void changeRole_creatorWithPendingClaimThrowsConflict() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.CREATOR).status(UserStatus.ACTIVE).build();
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        when(claims.existsByClaimantIdAndStatus(userId, com.influencermatch.backend.creator.enums.CreatorClaimStatus.PENDING))
                .thenReturn(true);

        assertThatThrownBy(() -> adminUserService.changeRole(
                userId, new UpdateUserRoleRequest(Role.BRAND, "Switch role")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(com.influencermatch.backend.exception.ErrorCode.CREATOR_ROLE_CHANGE_CONFLICT));
        verifyNoInteractions(refreshTokens, audit);
    }

    @Test
    @DisplayName("changeUserRole UTCID05 [A]: Last active admin to non-ADMIN throws LAST_ACTIVE_ADMIN")
    void changeRole_lastActiveAdminThrows() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.ADMIN).status(UserStatus.ACTIVE).build();
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        when(users.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> adminUserService.changeRole(
                userId, new UpdateUserRoleRequest(Role.DATA_MANAGER, "Demote")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(com.influencermatch.backend.exception.ErrorCode.LAST_ACTIVE_ADMIN));
    }

    @Test
    @DisplayName("changeUserRole UTCID06 [N]: Same role returns current response without token revocation or audit")
    void changeRole_sameRoleNoop() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.DATA_MANAGER).status(UserStatus.ACTIVE).build();
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));

        AdminUserResponse response = adminUserService.changeRole(
                userId, new UpdateUserRoleRequest(Role.DATA_MANAGER, "No change"));

        assertThat(response.role()).isEqualTo(Role.DATA_MANAGER);
        verifyNoInteractions(refreshTokens, audit);
    }
}
