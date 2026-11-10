package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.model.RefreshToken;
import com.influencermatch.backend.auth.repository.RefreshTokenRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
  private final RefreshTokenRepository tokens;
  private final UserRepository users;
  private final AuthSessionStore sessions;
  private final SecureRandom random = new SecureRandom();

  @Transactional
  public Issued issue(User user) {
    return issue(user, sessions.open(user, "INTERNAL").id());
  }

  @Transactional
  public Issued issue(User user, UUID sid) {
    users.lockRoleChanges();
    User current = users.findLockedByEmail(user.getEmail()).orElseThrow(this::invalid);
    check(current);
    if (current.getAuthVersion() != user.getAuthVersion() || current.getRole() != user.getRole())
      throw invalid();
    var session = sessions.require(sid, current, true);
    return create(current, session);
  }

  @Transactional(noRollbackFor = RefreshTokenReplayException.class)
  public Rotated rotate(String raw) {
    users.lockRoleChanges();
    var first = tokens.findByTokenHash(hash(raw)).orElseThrow(this::invalid);
    User user = users.findById(first.getUserId()).orElseThrow(this::invalid);
    user = users.findLockedByEmail(user.getEmail()).orElseThrow(this::invalid);
    check(user);
    var session = sessions.read(first.getSessionId(), true);
    var old = tokens.findByTokenHash(hash(raw)).orElseThrow(this::invalid);
    Instant now = sessions.now();
    if (old.getRevokedAt() != null) {
      sessions.revokeAll(user.getId());
      user.setAuthVersion(user.getAuthVersion() + 1);
      throw new RefreshTokenReplayException();
    }
    sessions.require(session.id(), user, false);
    if (!old.active(now)) throw invalid();
    old.setRevokedAt(now);
    var next = create(user, session);
    old.setReplacedByTokenId(next.entity().getId());
    return new Rotated(user, next.raw(), session.id());
  }

  @Transactional
  public void revoke(String raw) {
    if (raw == null || raw.isBlank()) return;
    users.lockRoleChanges();
    var t = tokens.findByTokenHash(hash(raw)).orElse(null);
    if (t == null || t.getSessionId() == null) return;
    var u = users.findById(t.getUserId()).orElse(null);
    if (u == null) return;
    users.findLockedByEmail(u.getEmail());
    sessions.read(t.getSessionId(), true);
    sessions.revoke(t.getSessionId());
  }

  @Transactional
  public void revokeAll(UUID userId) {
    sessions.revokeAll(userId);
  }

  @Transactional
  public void logoutAll(User actor) {
    users.lockRoleChanges();
    var u = users.findLockedByEmail(actor.getEmail()).orElseThrow(this::invalid);
    sessions.require(sessions.currentId(), u, true);
    u.setAuthVersion(u.getAuthVersion() + 1);
    sessions.revokeAll(u.getId());
  }

  private Issued create(User user, AuthSessionStore.Session session) {
    byte[] bytes = new byte[48];
    random.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var entity =
        tokens.save(
            RefreshToken.builder()
                .userId(user.getId())
                .sessionId(session.id())
                .tokenHash(hash(raw))
                .jti(UUID.randomUUID())
                .createdAt(sessions.now())
                .expiresAt(session.expiresAt())
                .build());
    return new Issued(raw, entity);
  }

  private void check(User u) {
    if (!u.isEmailVerified() || !u.isEnabled() || !u.isAccountNonLocked()) throw invalid();
  }

  private BusinessException invalid() {
    return new BusinessException(ErrorCode.UNAUTHENTICATED, "Refresh token is invalid or expired");
  }

  private String hash(String value) {
    if (value == null || value.isBlank()) throw invalid();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public record Issued(String raw, RefreshToken entity) {}

  public record Rotated(User user, String refreshToken, UUID sessionId) {}
}
