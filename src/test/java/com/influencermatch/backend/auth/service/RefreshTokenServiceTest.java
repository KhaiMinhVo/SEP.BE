package com.influencermatch.backend.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.auth.model.RefreshToken;
import com.influencermatch.backend.auth.repository.RefreshTokenRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {
  @Mock RefreshTokenRepository tokens;
  @Mock UserRepository users;
  @Mock AuthSessionStore sessions;
  @InjectMocks RefreshTokenService service;
  Instant now = Instant.parse("2026-11-07T00:00:00Z");
  User user;
  AuthSessionStore.Session session;

  @BeforeEach
  void setup() {
    user =
        User.builder()
            .id(UUID.randomUUID())
            .email("session@test.invalid")
            .role(Role.BRAND)
            .status(UserStatus.ACTIVE)
            .build();
    session =
        new AuthSessionStore.Session(
            UUID.randomUUID(), user.getId(), 0, now.plusSeconds(604800), false);
    lenient().when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
    lenient().when(users.findById(user.getId())).thenReturn(Optional.of(user));
    lenient().when(sessions.now()).thenReturn(now);
    lenient().when(sessions.open(user, "INTERNAL")).thenReturn(session);
    lenient().when(sessions.read(session.id(), true)).thenReturn(session);
    lenient().when(sessions.require(eq(session.id()), eq(user), anyBoolean())).thenReturn(session);
    lenient()
        .when(tokens.save(any()))
        .thenAnswer(
            a -> {
              RefreshToken t = a.getArgument(0);
              t.setId(UUID.randomUUID());
              return t;
            });
  }

  RefreshToken old() {
    return RefreshToken.builder()
        .id(UUID.randomUUID())
        .userId(user.getId())
        .sessionId(session.id())
        .expiresAt(session.expiresAt())
        .build();
  }

  @Test
  void issue_Success() {
    var issued = service.issue(user);
    assertThat(issued.raw()).isNotBlank();
    assertThat(issued.entity().getTokenHash()).doesNotContain(issued.raw());
    assertThat(issued.entity().getSessionId()).isEqualTo(session.id());
    assertThat(issued.entity().getExpiresAt()).isEqualTo(session.expiresAt());
    verify(users).lockRoleChanges();
  }

  @Test
  void issue_FailsAuthVersionMismatch() {
    var request =
        User.builder()
            .id(user.getId())
            .email(user.getEmail())
            .authVersion(9)
            .role(Role.BRAND)
            .build();
    assertThatThrownBy(() -> service.issue(request, session.id()))
        .isInstanceOf(BusinessException.class);
    verify(tokens, never()).save(any());
  }

  @Test
  void issue_FailsWhenUserNotActive() {
    user.setStatus(UserStatus.LOCKED);
    assertThatThrownBy(() -> service.issue(user)).isInstanceOf(BusinessException.class);
    verify(tokens, never()).save(any());
  }

  @Test
  void changedRoleCannotIssueTokenFromStalePrincipal() {
    var stale =
        User.builder()
            .id(user.getId())
            .email(user.getEmail())
            .role(Role.CREATOR)
            .authVersion(user.getAuthVersion())
            .status(UserStatus.ACTIVE)
            .build();
    assertThatThrownBy(() -> service.issue(stale, session.id()))
        .isInstanceOf(BusinessException.class);
    verify(tokens, never()).save(any());
  }

  @Test
  void rotate_Success() {
    var old = old();
    when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(old));
    var r = service.rotate("old");
    assertThat(r.refreshToken()).isNotBlank();
    assertThat(old.getRevokedAt()).isNotNull();
    assertThat(old.getReplacedByTokenId()).isNotNull();
    assertThat(r.sessionId()).isEqualTo(session.id());
    verify(users).lockRoleChanges();
  }

  @Test
  void rotate_FailsWhenRevokedAndRevokesAll() {
    var old = old();
    old.setRevokedAt(now.minusSeconds(10));
    when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(old));
    assertThatThrownBy(() -> service.rotate("old")).isInstanceOf(RefreshTokenReplayException.class);
    verify(sessions).revokeAll(user.getId());
    assertThat(user.getAuthVersion()).isEqualTo(1);
    verify(tokens, never()).save(any());
  }

  @Test
  void expiredTokenCannotRotate() {
    var old = old();
    old.setExpiresAt(now.minusSeconds(1));
    when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(old));
    assertThatThrownBy(() -> service.rotate("old")).isInstanceOf(BusinessException.class);
    assertThat(old.getRevokedAt()).isNull();
    verify(tokens, never()).save(any());
  }

  @Test
  void unknownTokenCannotRotate() {
    when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.rotate("unknown")).isInstanceOf(BusinessException.class);
    verify(tokens, never()).save(any());
  }

  @Test
  void revoke_Success() {
    var old = old();
    when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(old));
    service.revoke("old");
    verify(sessions).revoke(session.id());
  }

  @Test
  void revokeUnknownIsIdempotent() {
    when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());
    service.revoke("missing");
    verify(sessions, never()).revoke(any());
  }

  @Test
  void revokeAll_Success() {
    service.revokeAll(user.getId());
    verify(sessions).revokeAll(user.getId());
  }

  @Test
  void inactiveUserCannotRotate() {
    user.setStatus(UserStatus.DISABLED);
    when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(old()));
    assertThatThrownBy(() -> service.rotate("old")).isInstanceOf(BusinessException.class);
    verify(tokens, never()).save(any());
  }
}
