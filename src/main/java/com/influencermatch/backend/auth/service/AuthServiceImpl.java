package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.controller.*;
import com.influencermatch.backend.auth.dto.*;
import com.influencermatch.backend.auth.dto.LoginRequest;
import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.auth.dto.RefreshTokenRequest;
import com.influencermatch.backend.auth.dto.RegisterRequest;
import com.influencermatch.backend.auth.dto.UserProfileResponse;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.RefreshTokenReplayException;
import com.influencermatch.backend.security.jwt.JwtTokenProvider;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.security.permission.service.EffectivePermissionService;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  private final UserRepository userRepository;
  private final RegistrationSupport registration;
  private final AuthSessionIssuer issuer;
  private final com.influencermatch.backend.brand.repository.BrandProfileRepository profiles;
  private final EmailVerificationService verification;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;
  private final AuthenticationManager authenticationManager;
  private final RefreshTokenService refreshTokenService;
  private final EffectivePermissionService effectivePermissions;

  @Override
  @Transactional
  public void register(RegisterRequest request) {
    registration.validate(request.getPolicyAcceptance());
    RegistrationSupport.phone(request.getPhoneNumber());
    verification.requireConfigured();
    String normalizedEmail = request.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
    userRepository.lockRegistrationEmail(normalizedEmail);

    if (userRepository.existsByEmail(normalizedEmail)) {
      throw new BusinessException(
          ErrorCode.EMAIL_ALREADY_EXISTS, "An account with this email already exists.");
    }

    User user =
        User.builder()
            .email(normalizedEmail)
            .passwordHash(passwordEncoder.encode(request.getPassword()))
            .fullName(request.getFullName().trim())
            .role(Role.BRAND)
            .emailVerified(false) // new users must verify email
            .status(UserStatus.ACTIVE)
            .build();

    userRepository.saveAndFlush(user);
    registration.brand(user, request.getBusinessName(), request.getPhoneNumber());
    registration.consent(user, request.getPolicyAcceptance(), "PASSWORD_REGISTRATION");
    verification.sendInitial(user);
    log.info("Registered new user: email='{}'", normalizedEmail);
  }

  @Override
  @Transactional
  public LoginResponse login(LoginRequest request) {
    userRepository.lockRoleChanges();
    Authentication authentication =
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                request.getEmail().trim().toLowerCase(), request.getPassword()));

    User user = (User) authentication.getPrincipal();
    if (!user.isEmailVerified())
      throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED, "Verify your email before login");
    user.setLastLoginAt(LocalDateTime.now());
    userRepository.save(user);
    return issuer.issue(user, "PASSWORD");
  }

  @Override
  @Transactional(noRollbackFor = RefreshTokenReplayException.class)
  public LoginResponse refresh(RefreshTokenRequest request) {
    RefreshTokenService.Rotated rotated = refreshTokenService.rotate(request.refreshToken());
    return issuer.response(rotated.user(), rotated.sessionId(), rotated.refreshToken());
  }

  @Override
  @Transactional
  public void logout(RefreshTokenRequest request) {
    refreshTokenService.revoke(request.refreshToken());
  }

  @Override
  public UserProfileResponse getProfile(User currentUser) {
    Permissions.require(Permission.VIEW_OWN_PROFILE);
    var result =
        UserProfileResponse.builder()
            .id(currentUser.getId())
            .email(currentUser.getEmail())
            .fullName(currentUser.getFullName())
            .role(currentUser.getRole().name())
            .status(currentUser.getStatus().name())
            .permissions(effectivePermissions.names(currentUser.getRole()))
            .createdAt(currentUser.getCreatedAt())
            .updatedAt(currentUser.getUpdatedAt())
            .lastLoginAt(currentUser.getLastLoginAt())
            .build();
    if (currentUser.getRole() == Role.BRAND) {
      result.setOnboardingStatus("PENDING");
      profiles
          .findByUserId(currentUser.getId())
          .ifPresent(
              p -> {
                result.setBrandProfileId(p.getId());
                result.setOnboardingStatus(p.getOnboardingStatus());
              });
    }
    return result;
  }
}
