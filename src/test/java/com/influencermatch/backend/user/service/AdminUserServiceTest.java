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
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(User user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    @Test
    @DisplayName("list - Success")
    void list_Success() {
        Page<User> usersPage = new PageImpl<>(List.of(User.builder().id(UUID.randomUUID()).role(Role.BRAND).build()));
        when(users.findAll(any(PageRequest.class))).thenReturn(usersPage);
        
        Page<AdminUserResponse> result = adminUserService.list(PageRequest.of(0, 10));
        
        assertThat(result).hasSize(1);
        verify(users).findAll(any(PageRequest.class));
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
        verify(users).lockRoleChanges();
        verify(refreshTokens).revokeAll(userId);
        verify(audit).record(eq("CHANGE_USER_STATUS"), eq("User"), eq(userId), anyMap(), anyMap(), eq("Violated terms"));
    }

    @Test
    @DisplayName("changeStatus - Same Status, No Action")
    void changeStatus_SameStatus() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.BRAND).status(UserStatus.ACTIVE).build();
        UpdateUserStatusRequest request = new UpdateUserStatusRequest(UserStatus.ACTIVE, "Re-enable");
        
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        
        AdminUserResponse response = adminUserService.changeStatus(userId, request);
        
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        verify(users, never()).save(any());
        verify(audit, never()).record(anyString(), anyString(), any(), anyMap(), anyMap(), anyString());
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
    @DisplayName("changeRole - Success")
    void changeRole_Success() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.DATA_MANAGER).status(UserStatus.ACTIVE).authVersion(1L).build();
        UpdateUserRoleRequest request = new UpdateUserRoleRequest(Role.ADMIN, "Promoted");
        
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        
        AdminUserResponse response = adminUserService.changeRole(userId, request);
        
        assertThat(response.role()).isEqualTo(Role.ADMIN);
        assertThat(targetUser.getAuthVersion()).isEqualTo(2L);
        verify(users).lockRoleChanges();
        verify(refreshTokens).revokeAll(userId);
        verify(audit).record(eq("ASSIGN_USER_ROLE"), eq("User"), eq(userId), anyMap(), anyMap(), eq("Promoted"));
    }

    @Test
    @DisplayName("changeRole - Prevent Changing Brand if Profile Exists")
    void changeRole_PreventChangingBrandWithProfile() {
        UUID userId = UUID.randomUUID();
        User targetUser = User.builder().id(userId).role(Role.BRAND).status(UserStatus.ACTIVE).build();
        UpdateUserRoleRequest request = new UpdateUserRoleRequest(Role.DATA_MANAGER, "Demoted");
        
        when(users.findById(userId)).thenReturn(Optional.of(targetUser));
        when(profiles.existsByUserId(userId)).thenReturn(true);
        
        assertThatThrownBy(() -> adminUserService.changeRole(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Use a separate internal account");
    }
}
