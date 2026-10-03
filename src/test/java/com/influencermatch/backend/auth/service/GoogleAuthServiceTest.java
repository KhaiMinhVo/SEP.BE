package com.influencermatch.backend.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.config.GoogleOAuthProperties;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.JwtTokenProvider;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class GoogleAuthServiceTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = UserStatus.class,
      names = {"LOCKED", "DISABLED"})
  void inactiveLinkedUserCannotLoginOrExchange(UserStatus status) {
    User user =
        User.builder()
            .id(UUID.randomUUID())
            .email("blocked@test.local")
            .role(Role.BRAND)
            .status(status)
            .build();
    when(identities.findByProviderAndProviderSubject(any(), any()))
        .thenReturn(Optional.of(ExternalIdentity.builder().user(user).build()));
    assertThatThrownBy(() -> service.completeGoogleLogin("sub", user.getEmail(), "Brand", true))
        .isInstanceOf(BusinessException.class);
    verify(exchangeCodes, never()).save(any());
    when(exchangeCodes.findByCodeHash(anyString()))
        .thenReturn(
            Optional.of(
                AuthExchangeCode.builder()
                    .userId(user.getId())
                    .expiresAt(LocalDateTime.now().plusMinutes(1))
                    .build()));
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    assertThatThrownBy(() -> service.exchange("code")).isInstanceOf(BusinessException.class);
    verifyNoInteractions(jwtTokenProvider, refreshTokens);
  }

  @Test
  void expiredCodeDoesNotIssueTokens() {
    when(exchangeCodes.findByCodeHash(anyString()))
        .thenReturn(
            Optional.of(
                AuthExchangeCode.builder().expiresAt(LocalDateTime.now().minusSeconds(1)).build()));
    assertThatThrownBy(() -> service.exchange("expired"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CODE_INVALID_OR_EXPIRED));
    verifyNoInteractions(jwtTokenProvider, refreshTokens);
  }

  @Mock UserRepository users;
  @Mock ExternalIdentityRepository identities;
  @Mock AuthExchangeCodeRepository exchangeCodes;
  @Mock JwtTokenProvider jwtTokenProvider;
  @Mock RefreshTokenService refreshTokens;
  GoogleAuthService service;

  @BeforeEach
  void setUp() {
    GoogleOAuthProperties properties = new GoogleOAuthProperties();
    properties.setExchangeCodeTtlSeconds(60);
    service =
        new GoogleAuthService(
            users, identities, exchangeCodes, jwtTokenProvider, refreshTokens, properties);
  }

  @Test
  void firstGoogleLoginCreatesActiveBrandWithoutPassword() {
    when(identities.findByProviderAndProviderSubject(any(), eq("google-sub")))
        .thenReturn(Optional.empty());
    when(users.findByEmail("brand@example.com")).thenReturn(Optional.empty());
    when(users.save(any()))
        .thenAnswer(
            invocation -> {
              User user = invocation.getArgument(0);
              user.setId(UUID.randomUUID());
              return user;
            });
    when(identities.findByUserIdAndProvider(any(), any())).thenReturn(Optional.empty());

    String code =
        service.completeGoogleLogin("google-sub", " Brand@Example.com ", "Google Brand", true);

    assertThat(code).isNotBlank();
    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(users).save(userCaptor.capture());
    assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.BRAND);
    assertThat(userCaptor.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(userCaptor.getValue().getPasswordHash()).isNull();
    verify(identities)
        .save(
            argThat(
                identity ->
                    identity.isEmailVerified()
                        && identity.getProvider() == ExternalIdentityProvider.GOOGLE));
    verify(exchangeCodes)
        .save(
            argThat(
                saved ->
                    saved.getCodeHash() != null
                        && saved.getCodeHash().length() == 64
                        && saved.getExpiresAt().isAfter(saved.getCreatedAt())));
  }

  @Test
  void existingAdminCannotBeLinked() {
    User admin =
        User.builder()
            .email("admin@example.com")
            .role(Role.ADMIN)
            .status(UserStatus.ACTIVE)
            .build();
    when(identities.findByProviderAndProviderSubject(any(), any())).thenReturn(Optional.empty());
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

    assertThatThrownBy(() -> service.completeGoogleLogin("sub", "admin@example.com", "Admin", true))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).code())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    verify(identities, never()).save(any());
  }

  @Test
  void unverifiedGoogleEmailIsRejected() {
    assertThatThrownBy(
            () -> service.completeGoogleLogin("sub", "brand@example.com", "Brand", false))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).code())
        .isEqualTo(ErrorCode.GOOGLE_EMAIL_NOT_VERIFIED);
  }

  @Test
  void existingDataManagerCannotBeLinked() {
    User manager =
        User.builder()
            .email("manager@example.test")
            .role(Role.DATA_MANAGER)
            .status(UserStatus.ACTIVE)
            .build();
    when(identities.findByProviderAndProviderSubject(any(), any())).thenReturn(Optional.empty());
    when(users.findByEmail(manager.getEmail())).thenReturn(Optional.of(manager));
    assertThatThrownBy(
            () -> service.completeGoogleLogin("sub", manager.getEmail(), "Manager", true))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).code())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    verify(identities, never()).save(any());
  }

  @Test
  void exchangeCodeCanBeUsedOnceAndIssuesInternalTokens() {
    User user =
        User.builder()
            .email("brand@example.com")
            .fullName("Brand")
            .role(Role.BRAND)
            .status(UserStatus.ACTIVE)
            .build();
    user.setId(UUID.randomUUID());
    AuthExchangeCode stored =
        AuthExchangeCode.builder()
            .userId(user.getId())
            .codeHash("hash")
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusMinutes(1))
            .build();
    when(exchangeCodes.findByCodeHash(anyString())).thenReturn(Optional.of(stored));
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(jwtTokenProvider.generateToken(user)).thenReturn("access-token");
    when(jwtTokenProvider.extractExpiresInSeconds("access-token")).thenReturn(900L);
    RefreshToken refreshEntity = RefreshToken.builder().userId(user.getId()).build();
    when(refreshTokens.issue(user))
        .thenReturn(new RefreshTokenService.Issued("refresh-token", refreshEntity));

    var response = service.exchange("one-time-code");
    assertThat(response.getAccessToken()).isEqualTo("access-token");
    assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
    assertThat(stored.getConsumedAt()).isNotNull();
    assertThat(user.getLastLoginAt()).isNotNull();

    assertThatThrownBy(() -> service.exchange("one-time-code"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).code())
        .isEqualTo(ErrorCode.AUTH_CODE_INVALID_OR_EXPIRED);
  }
}
