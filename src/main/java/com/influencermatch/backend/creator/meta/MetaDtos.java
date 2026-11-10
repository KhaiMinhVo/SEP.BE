package com.influencermatch.backend.creator.meta;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class MetaDtos {
  private MetaDtos() {}

  public record Start(
      @NotBlank @Pattern(regexp = "FACEBOOK|INSTAGRAM") String platform,
      UUID creatorId,
      @Size(max = 512) String invitationToken) {
    @Override
    public String toString() {
      return "MetaStart[platform=" + platform + ", secrets redacted]";
    }
  }

  public record Started(UUID sessionId, String authorizationUrl, Instant expiresAt) {
    @Override
    public String toString() {
      return "MetaStarted[sessionId=" + sessionId + "]";
    }
  }

  public record InstagramStart(UUID creatorId, @Size(max = 512) String invitationToken) {
    @Override
    public String toString() {
      return "InstagramStart[secrets redacted]";
    }
  }

  public record Callback(UUID sessionId, String status) {}

  public record Confirm(@NotBlank @Size(max = 255) String accountId) {}

  public record Account(
      String accountId,
      String pageId,
      String name,
      String username,
      UUID creatorId,
      String matchStatus) {}

  public record Session(
      UUID sessionId,
      String platform,
      String status,
      Instant expiresAt,
      List<Account> accounts,
      OAuthFlow oauthFlow) {}

  public record Connected(UUID sessionId, UUID creatorId, UUID masterId, String status) {}

  public record Asset(String id, String pageId, String name, String username) {}

  public record Proof(
      String subject,
      String token,
      Instant tokenExpiresAt,
      Set<String> scopes,
      List<Asset> assets) {
    // For Instagram without provider expiry, tokenExpiresAt bounds proof validity only;
    // the session is never extended and the provider is rechecked before confirmation.
    @Override
    public String toString() {
      return "MetaProof[redacted]";
    }
  }
}
