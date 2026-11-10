package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.jwt.JwtTokenProvider;
import com.influencermatch.backend.security.permission.service.EffectivePermissionService;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthSessionIssuer {
  private final AuthSessionStore sessions;
  private final RefreshTokenService refresh;
  private final JwtTokenProvider jwt;
  private final EffectivePermissionService permissions;
  private final BrandProfileRepository profiles;

  @Transactional
  public LoginResponse issue(User user, String method) {
    if (!user.isEmailVerified() || !user.isEnabled() || !user.isAccountNonLocked())
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Account unavailable");
    user.setLastLoginAt(LocalDateTime.ofInstant(sessions.now(), ZoneOffset.UTC));
    var session = sessions.open(user, method);
    var issued = refresh.issue(user, session.id());
    return response(user, session.id(), issued.raw());
  }

  public LoginResponse response(User user, UUID sid, String raw) {
    var session = sessions.require(sid, user, false);
    String access = jwt.generateToken(user, sid, session.expiresAt());
    var profile =
        LoginResponse.UserProfile.builder()
            .id(user.getId())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .role(user.getRole().name())
            .status(user.getStatus().name())
            .permissions(permissions.names(user.getRole()))
            .lastLoginAt(user.getLastLoginAt())
            .build();
    if (user.getRole() == Role.BRAND) {
      profile.setOnboardingStatus("PENDING");
      profiles
          .findByUserId(user.getId())
          .ifPresent(
              p -> {
                profile.setBrandProfileId(p.getId());
                profile.setOnboardingStatus(p.getOnboardingStatus());
              });
    }
    return LoginResponse.builder()
        .accessToken(access)
        .refreshToken(raw)
        .refreshExpiresAt(session.expiresAt())
        .expiresIn(jwt.extractExpiresInSeconds(access))
        .user(profile)
        .build();
  }
}
