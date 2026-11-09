package com.influencermatch.backend.creator.tiktok;

import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import com.influencermatch.backend.creator.client.provider.TikTokSandboxProofProvider.VerifiedAccount;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TikTokIdentityResolver {
  private final CrawlerServiceClient crawler;
  private final Clock clock;

  public record Identity(
      String authorId, String username, String displayName, String avatarUrl, Instant observedAt) {}

  public Identity resolve(VerifiedAccount proof) {
    String username = TikTokUsernames.normalize(proof.username());
    if (!username.matches("[a-z0-9._]{1,255}")) throw invalid();
    var result = crawler.resolveTikTokIdentity(username);
    if (result == null
        || !"success".equals(result.getStatus())
        || !"TIKTOK".equals(result.getPlatform())
        || !"TIKTOK_USER_ID".equals(result.getIdType())
        || result.getAuthorId() == null
        || !result.getAuthorId().matches("[1-9][0-9]{0,19}")
        || !username.equals(TikTokUsernames.normalize(result.getResolvedUsername())))
      throw invalid();
    var info = result.getCreatorInfo();
    var identity =
        new Identity(
            result.getAuthorId(),
            username,
            info == null ? null : bounded(info.getDisplayName(), 255),
            info == null ? null : avatar(info.getAvatarUrl()),
            result.getObservedAt());
    requireFresh(identity);
    return identity;
  }

  public void requireFresh(Identity identity) {
    Instant now = clock.instant();
    if (identity.observedAt() == null
        || identity.observedAt().isBefore(now.minusSeconds(90))
        || identity.observedAt().isAfter(now.plusSeconds(5))) throw invalid();
  }

  private static String bounded(String value, int max) {
    return value == null || value.isBlank() || value.length() > max ? null : value.trim();
  }

  private static String avatar(String value) {
    value = bounded(value, 1000);
    if (value == null) return null;
    try {
      var uri = URI.create(value);
      return "https".equalsIgnoreCase(uri.getScheme())
              && uri.getHost() != null
              && uri.getUserInfo() == null
          ? value
          : null;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static BusinessException invalid() {
    return new BusinessException(
        ErrorCode.TIKTOK_IDENTITY_RESOLUTION_FAILED,
        "Unable to verify TikTok identity; start a new connection");
  }
}
