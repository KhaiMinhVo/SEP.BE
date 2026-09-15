package com.influencermatch.backend.service;

import com.influencermatch.backend.dto.auth.LoginRequest;
import com.influencermatch.backend.dto.auth.LoginResponse;
import com.influencermatch.backend.dto.auth.RegisterRequest;
import com.influencermatch.backend.dto.auth.UserProfileResponse;
import com.influencermatch.backend.dto.auth.RefreshTokenRequest;
import com.influencermatch.backend.entity.Role;
import com.influencermatch.backend.entity.User;
import com.influencermatch.backend.entity.UserStatus;
import com.influencermatch.backend.exception.BadRequestException;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.repository.UserRepository;
import com.influencermatch.backend.security.JwtTokenProvider;
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

    private final UserRepository        userRepository;
    private final PasswordEncoder       passwordEncoder;
    private final JwtTokenProvider      jwtTokenProvider;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokenService;

    @Override
    @Transactional
    public void register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "An account with this email already exists.");
        }

        User user = User.builder()
                .email(normalizedEmail)
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName().trim())
                .role(Role.BRAND)   // new users default to BRAND
                .status(UserStatus.ACTIVE)
                .build();

        userRepository.save(user);
        log.info("Registered new user: email='{}'", normalizedEmail);
    }

    @Override
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail().trim().toLowerCase(),
                        request.getPassword()
                )
        );

        User user = (User) authentication.getPrincipal();
        String accessToken = jwtTokenProvider.generateToken(user);
        String refreshToken = refreshTokenService.issue(user).raw();

        log.info("User logged in: email='{}', role='{}'", user.getEmail(), user.getRole());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.extractExpiresInSeconds(accessToken))
                .user(LoginResponse.UserProfile.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .fullName(user.getFullName())
                        .role(user.getRole().name())
                        .status(user.getStatus().name())
                        .build())
                .build();
    }

    @Override
    @Transactional
    public LoginResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.Rotated rotated = refreshTokenService.rotate(request.refreshToken());
        User user = rotated.user();
        String accessToken = jwtTokenProvider.generateToken(user);
        return LoginResponse.builder().accessToken(accessToken).refreshToken(rotated.refreshToken())
                .tokenType("Bearer").expiresIn(jwtTokenProvider.extractExpiresInSeconds(accessToken))
                .user(LoginResponse.UserProfile.builder().id(user.getId()).email(user.getEmail()).fullName(user.getFullName()).role(user.getRole().name()).status(user.getStatus().name()).build()).build();
    }

    @Override @Transactional public void logout(RefreshTokenRequest request) { refreshTokenService.revoke(request.refreshToken()); }

    @Override
    public UserProfileResponse getProfile(User currentUser) {
        return UserProfileResponse.builder()
                .id(currentUser.getId())
                .email(currentUser.getEmail())
                .fullName(currentUser.getFullName())
                .role(currentUser.getRole().name())
                .status(currentUser.getStatus().name())
                .createdAt(currentUser.getCreatedAt())
                .updatedAt(currentUser.getUpdatedAt())
                .build();
    }
}
