package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.model.User;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.*;

@Service
@RequiredArgsConstructor
public class AuthSessionStore {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public record Session(UUID id, UUID userId, long version, Instant expiresAt, boolean revoked) {}

  @Transactional
  public Session open(User user, String method) {
    Instant now = clock.instant();
    UUID id = UUID.randomUUID();
    Instant expires = now.plusSeconds(7 * 86400);
    jdbc.update(
        "insert into auth_session(id,user_id,auth_version,login_method,created_at,expires_at)"
            + " values(?,?,?,?,?,?)",
        id,
        user.getId(),
        user.getAuthVersion(),
        method,
        Timestamp.from(now),
        Timestamp.from(expires));
    return new Session(id, user.getId(), user.getAuthVersion(), expires, false);
  }

  public Session read(UUID id, boolean lock) {
    if (id == null) throw invalid();
    var rows =
        jdbc.query(
            "select id,user_id,auth_version,expires_at,revoked_at from auth_session where id=?"
                + (lock ? " for update" : ""),
            (rs, n) ->
                new Session(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getLong(3),
                    rs.getTimestamp(4).toInstant(),
                    rs.getTimestamp(5) != null),
            id);
    if (rows.isEmpty()) throw invalid();
    return rows.getFirst();
  }

  public Session require(UUID id, User user, boolean lock) {
    var s = read(id, lock);
    if (!s.userId().equals(user.getId())
        || s.version() != user.getAuthVersion()
        || s.revoked()
        || !s.expiresAt().isAfter(clock.instant())) throw invalid();
    return s;
  }

  public boolean valid(UUID id, User user) {
    try {
      require(id, user, false);
      return true;
    } catch (BusinessException ex) {
      return false;
    }
  }

  @Transactional
  public void revoke(UUID id) {
    jdbc.update(
        "update auth_session set revoked_at=coalesce(revoked_at,?) where id=?",
        Timestamp.from(clock.instant()),
        id);
    jdbc.update(
        "update refresh_token set revoked_at=coalesce(revoked_at,?) where session_id=?",
        Timestamp.from(clock.instant()),
        id);
  }

  @Transactional
  public void revokeAll(UUID user) {
    jdbc.update(
        "update auth_session set revoked_at=coalesce(revoked_at,?) where user_id=?",
        Timestamp.from(clock.instant()),
        user);
    jdbc.update(
        "update refresh_token set revoked_at=coalesce(revoked_at,?) where user_id=?",
        Timestamp.from(clock.instant()),
        user);
  }

  public UUID currentId() {
    var attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes a
        && a.getRequest().getAttribute("authSessionId") instanceof UUID id) return id;
    throw invalid();
  }

  public Instant now() {
    return clock.instant();
  }

  private BusinessException invalid() {
    return new BusinessException(
        ErrorCode.UNAUTHENTICATED, "Session is invalid, revoked or expired");
  }
}
