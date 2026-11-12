package com.influencermatch.backend.creator.tiktok;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class TikTokDtos {
  private TikTokDtos() {}

  public record Start(UUID creatorId, @Size(max = 512) String invitationToken) {
    @Override
    public String toString() {
      return "TikTokStart[secrets redacted]";
    }
  }

  public record Started(UUID sessionId, String authorizationUrl, Instant expiresAt) {
    @Override
    public String toString() {
      return "TikTokStarted[sessionId=" + sessionId + "]";
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Callback(UUID sessionId, String status, UUID creatorId, UUID masterId) {
    public Callback(UUID sessionId, String status) {
      this(sessionId, status, null, null);
    }
  }

  public record Account(String openId, String username, String displayName, String matchStatus) {}

  public record Session(
      UUID sessionId,
      String platform,
      String status,
      Instant expiresAt,
      Account account,
      UUID creatorId,
      UUID masterId) {}
}
