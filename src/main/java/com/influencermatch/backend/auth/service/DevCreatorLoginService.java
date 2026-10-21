package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.ExternalIdentityRepository;
import com.influencermatch.backend.security.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates fresh disposable identities, never impersonates or links an existing account. */
@Service @RequiredArgsConstructor
@Profile("dev & !prod")
@ConditionalOnProperty(name="application.auth.creator-mock.enabled",havingValue="true")
public class DevCreatorLoginService {
  private final UserRepository users;
  private final ExternalIdentityRepository identities;
  private final JwtTokenProvider jwt;
  private final RefreshTokenService refreshTokens;

  @Transactional
  public LoginResponse login() {
    users.lockRoleChanges();
    String subject=UUID.randomUUID().toString();
    User user=users.saveAndFlush(User.builder().email("creator-"+subject+"@creator-mock.invalid")
        .fullName("Development Mock Creator").role(Role.CREATOR).status(UserStatus.ACTIVE)
        .lastLoginAt(LocalDateTime.now()).build());
    identities.save(ExternalIdentity.builder().user(user).provider(ExternalIdentityProvider.DEV_MOCK)
        .providerSubject(subject).providerEmail(user.getEmail()).emailVerified(false).build());
    String access=jwt.generateToken(user);
    return LoginResponse.builder().accessToken(access).refreshToken(refreshTokens.issue(user).raw())
        .tokenType("Bearer").expiresIn(jwt.extractExpiresInSeconds(access))
        .user(LoginResponse.UserProfile.builder().id(user.getId()).email(user.getEmail()).fullName(user.getFullName())
            .role(user.getRole().name()).status(user.getStatus().name()).permissions(RolePermissions.names(user.getRole()))
            .lastLoginAt(user.getLastLoginAt()).build()).build();
  }
}
