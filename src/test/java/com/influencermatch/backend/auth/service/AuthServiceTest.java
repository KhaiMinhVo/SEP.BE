package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.dto.LoginRequest;
import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.auth.dto.RegisterRequest;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.security.JwtTokenProvider;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthServiceImpl authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = createMockUser();
    }

    @Test
    @DisplayName("Register - Valid request saves new user successfully")
    void register_ValidRequest_SavesUser() {
        // GIVEN
        RegisterRequest request = createValidRegisterRequest();
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("hashed_password");

        // WHEN
        authService.register(request);

        // THEN
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("Register - Existing email throws BusinessException")
    void register_EmailExists_ThrowsException() {
        // GIVEN
        RegisterRequest request = createValidRegisterRequest();
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(true);

        // WHEN & THEN
        assertThrows(BusinessException.class, () -> authService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Login - Valid credentials returns access and refresh tokens")
    void login_ValidRequest_ReturnsTokens() {
        // GIVEN
        LoginRequest request = createValidLoginRequest();
        
        Authentication auth = mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(auth);
        when(auth.getPrincipal()).thenReturn(testUser);
        
        when(jwtTokenProvider.generateToken(testUser)).thenReturn("mocked.access.token");
        when(jwtTokenProvider.extractExpiresInSeconds("mocked.access.token")).thenReturn(3600L);
        
        RefreshTokenService.Issued mockIssued = new RefreshTokenService.Issued("mocked.refresh.token", null);
        when(refreshTokenService.issue(testUser)).thenReturn(mockIssued);

        // WHEN
        LoginResponse response = authService.login(request);

        // THEN
        assertNotNull(response);
        assertEquals("mocked.access.token", response.getAccessToken());
        assertEquals("mocked.refresh.token", response.getRefreshToken());
        verify(userRepository, times(1)).save(testUser); // Verifies last login update
    }

    private User createMockUser() {
        return User.builder()
                .email("test@example.com")
                .passwordHash("hashed_password")
                .fullName("Test User")
                .role(Role.BRAND)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private RegisterRequest createValidRegisterRequest() {
        return RegisterRequest.builder()
                .email("new@example.com")
                .password("Password123!")
                .fullName("New User")
                .build();
    }

    private LoginRequest createValidLoginRequest() {
        return LoginRequest.builder()
                .email("test@example.com")
                .password("Password123!")
                .build();
    }
}
