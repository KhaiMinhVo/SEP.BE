package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.ExternalIdentityRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.jwt.JwtTokenProvider;
import com.influencermatch.backend.security.permission.service.EffectivePermissionService;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GoogleBrandLoginService {
  private final GoogleIdTokenVerifier verifier;
  private final UserRepository users;
  private final ExternalIdentityRepository identities;
  private final RefreshTokenService refresh;
  private final JwtTokenProvider jwt;
  private final EffectivePermissionService permissions;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public record Start(UUID sessionId, String clientId, String nonce, Instant expiresAt) {}

  @Transactional
  public Start start(String browser) {
    String clientId = verifier.requireConfigured();
    UUID id = UUID.randomUUID();
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expires = clock.instant().plusSeconds(600);
    jdbc.update(
        "delete from google_login_session where expires_at < ?",
        Timestamp.from(clock.instant().minusSeconds(86400)));
    jdbc.update(
        "insert into google_login_session(id,nonce_hash,browser_hash,expires_at) values (?,?,?,?)",
        id,
        hash(nonce),
        hash(browser),
        Timestamp.from(expires));
    return new Start(id, clientId, nonce, expires);
  }

  /** Provider verification happens before entering this transaction or acquiring database locks. */
  @Transactional
  public LoginResponse finish(UUID sessionId, String browser, Jwt proof) {
    verifier.requireConfigured();
    String email = proof.getClaimAsString("email").trim().toLowerCase(Locale.ROOT);
    users.lockRoleChanges();
    users.lockRegistrationEmail(email);
    var sessions =
        jdbc.query(
            "select nonce_hash,browser_hash,expires_at,consumed_at from google_login_session where"
                + " id=? for update",
            (rs, row) ->
                new Session(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getTimestamp(3).toInstant(),
                    rs.getTimestamp(4) != null),
            sessionId);
    if (sessions.isEmpty()) throw invalid();
    Session session = sessions.getFirst();
    if (browser == null || !session.browserHash.equals(hash(browser))) throw invalid();
    if (session.consumed)
      throw new BusinessException(ErrorCode.GOOGLE_LOGIN_REPLAY, "Start Google Login again");
    if (!session.expires.isAfter(clock.instant())
        || proof.getExpiresAt() == null
        || !proof.getExpiresAt().isAfter(clock.instant())
        || !session.nonceHash.equals(hash(proof.getClaimAsString("nonce")))) throw invalid();

    ExternalIdentity identity =
        identities
            .findByProviderAndProviderSubject(ExternalIdentityProvider.GOOGLE, proof.getSubject())
            .orElse(null);
    User user;
    if (identity != null) {
      user = users.findLockedByEmail(identity.getUser().getEmail()).orElseThrow(this::invalid);
      // Identity is keyed by Google subject; a changed email never relinks to another User.
    } else {
      User existing = users.findLockedByEmail(email).orElse(null);
      if (existing != null) {
        if (!existing.isEmailVerified())
          throw new BusinessException(
              ErrorCode.EMAIL_NOT_VERIFIED, "Complete email verification first");
        throw new BusinessException(
            ErrorCode.GOOGLE_ACCOUNT_LINK_REQUIRED,
            "Sign in with your existing account; automatic email linking is disabled");
      }
      String name = proof.getClaimAsString("name");
      if (name == null || name.isBlank()) name = "Brand";
      name = name.trim();
      user =
          users.saveAndFlush(
              User.builder()
                  .email(email)
                  .fullName(name.substring(0, Math.min(150, name.length())))
                  .role(Role.BRAND)
                  .status(UserStatus.ACTIVE)
                  .emailVerified(true)
                  .build());
      identities.saveAndFlush(
          ExternalIdentity.builder()
              .user(user)
              .provider(ExternalIdentityProvider.GOOGLE)
              .providerSubject(proof.getSubject())
              .providerEmail(email)
              .emailVerified(true)
              .build());
    }
    if (user.getRole() != Role.BRAND)
      throw new BusinessException(
          ErrorCode.ACCESS_DENIED, "Google Login is available to Brand accounts only");
    if (!user.isEnabled())
      throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "Account disabled");
    if (!user.isAccountNonLocked())
      throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "Account locked");
    if (!user.isEmailVerified())
      throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED, "Verify your email first");
    user.setLastLoginAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    String access = jwt.generateToken(user);
    String refreshToken = refresh.issue(user).raw();
    jdbc.update(
        "update google_login_session set consumed_at=? where id=?",
        Timestamp.from(clock.instant()),
        sessionId);
    return LoginResponse.builder()
        .accessToken(access)
        .refreshToken(refreshToken)
        .expiresIn(jwt.extractExpiresInSeconds(access))
        .user(
            LoginResponse.UserProfile.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .status(user.getStatus().name())
                .permissions(permissions.names(user.getRole()))
                .lastLoginAt(user.getLastLoginAt())
                .build())
        .build();
  }

  private record Session(String nonceHash, String browserHash, Instant expires, boolean consumed) {}

  private BusinessException invalid() {
    return new BusinessException(
        ErrorCode.UNAUTHENTICATED, "Invalid or expired Google login session");
  }

  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
