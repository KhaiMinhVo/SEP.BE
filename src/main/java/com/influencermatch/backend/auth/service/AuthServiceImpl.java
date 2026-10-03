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
import com.influencermatch.backend.security.JwtTokenProvider;
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
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;
  private final AuthenticationManager authenticationManager;
  private final RefreshTokenService refreshTokenService;

  @Override
  @Transactional
  public void register(RegisterRequest request) {
    String normalizedEmail = request.getEmail().trim().toLowerCase();

    if (userRepository.existsByEmail(normalizedEmail)) {
      throw new BusinessException(
          ErrorCode.EMAIL_ALREADY_EXISTS, "An account with this email already exists.");
    }

    User user =
        User.builder()
            .email(normalizedEmail)
            .passwordHash(passwordEncoder.encode(request.getPassword()))
            .fullName(request.getFullName().trim())
            .role(Role.BRAND) // new users default to BRAND
            .status(UserStatus.ACTIVE)
            .build();

    userRepository.save(user);
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
    user.setLastLoginAt(LocalDateTime.now());
    userRepository.save(user);
    String accessToken = jwtTokenProvider.generateToken(user);
    String refreshToken = refreshTokenService.issue(user).raw();

    log.info("User logged in: email='{}', role='{}'", user.getEmail(), user.getRole());

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

  @Override
  @Transactional
  public LoginResponse refresh(RefreshTokenRequest request) {
    RefreshTokenService.Rotated rotated = refreshTokenService.rotate(request.refreshToken());
    User user = rotated.user();
    String accessToken = jwtTokenProvider.generateToken(user);
    return LoginResponse.builder()
        .accessToken(accessToken)
        .refreshToken(rotated.refreshToken())
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

  @Override
  @Transactional
  public void logout(RefreshTokenRequest request) {
    refreshTokenService.revoke(request.refreshToken());
  }

  @Override
  public UserProfileResponse getProfile(User currentUser) {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.VIEW_OWN_PROFILE);
    return UserProfileResponse.builder()
        .id(currentUser.getId())
        .email(currentUser.getEmail())
        .fullName(currentUser.getFullName())
        .role(currentUser.getRole().name())
        .status(currentUser.getStatus().name())
        .permissions(
            com.influencermatch.backend.security.RolePermissions.names(currentUser.getRole()))
        .createdAt(currentUser.getCreatedAt())
        .updatedAt(currentUser.getUpdatedAt())
        .lastLoginAt(currentUser.getLastLoginAt())
        .build();
  }
}
