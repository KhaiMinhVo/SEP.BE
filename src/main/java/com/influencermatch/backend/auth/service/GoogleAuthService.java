package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.config.GoogleOAuthProperties;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.JwtTokenProvider;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GoogleAuthService {
  private final UserRepository users;
  private final ExternalIdentityRepository identities;
  private final AuthExchangeCodeRepository exchangeCodes;
  private final JwtTokenProvider jwtTokenProvider;
  private final RefreshTokenService refreshTokens;
  private final GoogleOAuthProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  @Transactional
  public String completeGoogleLogin(
      String subject, String email, String fullName, boolean emailVerified) {
    if (!emailVerified)
      throw new BusinessException(
          ErrorCode.GOOGLE_EMAIL_NOT_VERIFIED, "Google account email is not verified");
    if (subject == null || subject.isBlank() || email == null || email.isBlank())
      throw new BusinessException(
          ErrorCode.GOOGLE_AUTHENTICATION_FAILED,
          "Google did not provide the required identity claims");

    String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
    ExternalIdentity identity =
        identities
            .findByProviderAndProviderSubject(ExternalIdentityProvider.GOOGLE, subject)
            .orElse(null);
    User user;
    if (identity != null) {
      user = identity.getUser();
      identity.setProviderEmail(normalizedEmail);
      identity.setEmailVerified(true);
    } else {
      user =
          users
              .findByEmail(normalizedEmail)
              .orElseGet(
                  () ->
                      users.save(
                          User.builder()
                              .email(normalizedEmail)
                              .passwordHash(null)
                              .fullName(normalizeName(fullName, normalizedEmail))
                              .role(Role.BRAND)
                              .status(UserStatus.ACTIVE)
                              .build()));
      requireBrand(user);
      identities
          .findByUserIdAndProvider(user.getId(), ExternalIdentityProvider.GOOGLE)
          .ifPresent(
              existing -> {
                throw new BusinessException(
                    ErrorCode.GOOGLE_AUTHENTICATION_FAILED,
                    "This account is linked to another Google identity");
              });
      identities.save(
          ExternalIdentity.builder()
              .user(user)
              .provider(ExternalIdentityProvider.GOOGLE)
              .providerSubject(subject)
              .providerEmail(normalizedEmail)
              .emailVerified(true)
              .build());
    }
    requireBrand(user);
    requireActive(user);
    return createExchangeCode(user);
  }

  @Transactional
  public LoginResponse exchange(String rawCode) {
    users.lockRoleChanges();
    LocalDateTime now = LocalDateTime.now();
    AuthExchangeCode code =
        exchangeCodes.findByCodeHash(hash(rawCode.trim())).orElseThrow(this::invalidCode);
    if (code.getConsumedAt() != null || !code.getExpiresAt().isAfter(now)) throw invalidCode();
    User user = users.findById(code.getUserId()).orElseThrow(this::invalidCode);
    requireBrand(user);
    requireActive(user);
    code.setConsumedAt(now);
    user.setLastLoginAt(now);
    String accessToken = jwtTokenProvider.generateToken(user);
    String refreshToken = refreshTokens.issue(user).raw();
    return LoginResponse.builder()
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .tokenType("Bearer")
        .expiresIn(jwtTokenProvider.extractExpiresInSeconds(accessToken))
        .user(
            LoginResponse.UserProfile.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .status(user.getStatus().name())
                .permissions(
                    com.influencermatch.backend.security.RolePermissions.names(user.getRole()))
                .lastLoginAt(user.getLastLoginAt())
                .build())
        .build();
  }

  private String createExchangeCode(User user) {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    LocalDateTime now = LocalDateTime.now();
    exchangeCodes.save(
        AuthExchangeCode.builder()
            .userId(user.getId())
            .codeHash(hash(raw))
            .createdAt(now)
            .expiresAt(now.plusSeconds(properties.getExchangeCodeTtlSeconds()))
            .build());
    return raw;
  }

  private void requireBrand(User user) {
    if (user.getRole() != Role.BRAND)
      throw new BusinessException(
          ErrorCode.ACCESS_DENIED, "Google login is available only for brand accounts");
  }

  private void requireActive(User user) {
    if (user.getStatus() == UserStatus.LOCKED)
      throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "Account is locked");
    if (user.getStatus() == UserStatus.DISABLED)
      throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
  }

  private String normalizeName(String value, String email) {
    if (value == null || value.isBlank()) return email.substring(0, email.indexOf('@'));
    String trimmed = value.trim();
    return trimmed.length() <= 150 ? trimmed : trimmed.substring(0, 150);
  }

  private BusinessException invalidCode() {
    return new BusinessException(
        ErrorCode.AUTH_CODE_INVALID_OR_EXPIRED,
        "Authentication code is invalid, expired, or already used");
  }

  private String hash(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
