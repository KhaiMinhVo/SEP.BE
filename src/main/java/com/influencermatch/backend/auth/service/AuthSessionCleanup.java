package com.influencermatch.backend.auth.service;

@org.springframework.stereotype.Component
@lombok.RequiredArgsConstructor
public class AuthSessionCleanup {
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final java.time.Clock clock;

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void cleanup() {
    jdbc.update(
        "update google_login_session set google_subject=null,google_email=null,google_name=null"
            + " where expires_at<=? and google_subject is not null",
        java.sql.Timestamp.from(clock.instant()));
  }
}
