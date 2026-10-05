package com.influencermatch.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.auth.model.RefreshToken;
import com.influencermatch.backend.auth.repository.RefreshTokenRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository tokens;

    @Mock
    private UserRepository users;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        // Set the mock expiration time (1 day in ms)
        ReflectionTestUtils.setField(refreshTokenService, "expirationMs", 86400000L);
    }

    @Test
    @DisplayName("issue - Success")
    void issue_Success() {
        UUID userId = UUID.randomUUID();
        User requestUser = User.builder().id(userId).role(Role.DATA_MANAGER).authVersion(1L).status(UserStatus.ACTIVE).build();
        User dbUser = User.builder().id(userId).role(Role.DATA_MANAGER).authVersion(1L).status(UserStatus.ACTIVE).build();

        when(users.findById(userId)).thenReturn(Optional.of(dbUser));
        when(tokens.save(any(RefreshToken.class))).thenAnswer(i -> {
            RefreshToken t = i.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        RefreshTokenService.Issued issued = refreshTokenService.issue(requestUser);

        assertThat(issued).isNotNull();
        assertThat(issued.raw()).isNotBlank();
        assertThat(issued.entity().getUserId()).isEqualTo(userId);
        
        verify(users).lockRoleChanges();
        verify(tokens).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("issue - Fails when auth version mismatches")
    void issue_FailsAuthVersionMismatch() {
        UUID userId = UUID.randomUUID();
        // The token expects version 1, but DB has version 2 (user might have changed password or role)
        User requestUser = User.builder().id(userId).role(Role.DATA_MANAGER).authVersion(1L).status(UserStatus.ACTIVE).build();
        User dbUser = User.builder().id(userId).role(Role.DATA_MANAGER).authVersion(2L).status(UserStatus.ACTIVE).build();

        when(users.findById(userId)).thenReturn(Optional.of(dbUser));

        assertThatThrownBy(() -> refreshTokenService.issue(requestUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Refresh token is invalid or expired")
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }
    
    @Test
    @DisplayName("issue - Fails when user is not active")
    void issue_FailsWhenUserNotActive() {
        UUID userId = UUID.randomUUID();
        User requestUser = User.builder().id(userId).role(Role.DATA_MANAGER).authVersion(1L).status(UserStatus.LOCKED).build();
        User dbUser = User.builder().id(userId).role(Role.DATA_MANAGER).authVersion(1L).status(UserStatus.LOCKED).build();

        when(users.findById(userId)).thenReturn(Optional.of(dbUser));

        assertThatThrownBy(() -> refreshTokenService.issue(requestUser))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("rotate - Success")
    void rotate_Success() {
        UUID userId = UUID.randomUUID();
        String rawToken = "old-raw-token";
        
        RefreshToken oldToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .expiresAt(LocalDateTime.now().plusDays(1)) // Still valid
                .build();
                
        User dbUser = User.builder().id(userId).role(Role.DATA_MANAGER).status(UserStatus.ACTIVE).build();

        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(oldToken));
        when(users.findById(userId)).thenReturn(Optional.of(dbUser));
        when(tokens.save(any(RefreshToken.class))).thenAnswer(i -> {
            RefreshToken t = i.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        RefreshTokenService.Rotated rotated = refreshTokenService.rotate(rawToken);

        assertThat(rotated).isNotNull();
        assertThat(rotated.refreshToken()).isNotBlank();
        
        // Ensure old token is revoked and linked to the new one
        assertThat(oldToken.getRevokedAt()).isNotNull();
        assertThat(oldToken.getReplacedByTokenId()).isNotNull();
        
        verify(users).lockRoleChanges();
    }

    @Test
    @DisplayName("rotate - Fails when token is revoked (Suspicious activity triggers revokeAll)")
    void rotate_FailsWhenRevokedAndRevokesAll() {
        UUID userId = UUID.randomUUID();
        String rawToken = "old-raw-token";
        
        RefreshToken oldToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .revokedAt(LocalDateTime.now().minusDays(1)) // Already revoked
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
                
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(oldToken));

        assertThatThrownBy(() -> refreshTokenService.rotate(rawToken))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.UNAUTHENTICATED);

        // Security check: attempting to use a revoked token revokes ALL tokens for that user
        verify(tokens).revokeAll(eq(userId), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("revoke - Success")
    void revoke_Success() {
        String rawToken = "some-token";
        RefreshToken token = RefreshToken.builder()
                .id(UUID.randomUUID())
                .build();
                
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        
        refreshTokenService.revoke(rawToken);
        
        assertThat(token.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("revokeAll - Success")
    void revokeAll_Success() {
        UUID userId = UUID.randomUUID();
        
        refreshTokenService.revokeAll(userId);
        
        verify(tokens).revokeAll(eq(userId), any(LocalDateTime.class));
    }
}
