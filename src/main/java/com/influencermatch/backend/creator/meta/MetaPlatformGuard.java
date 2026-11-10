package com.influencermatch.backend.creator.meta;

import com.influencermatch.backend.exception.*;

public final class MetaPlatformGuard {
  private MetaPlatformGuard() {}

  public static void requireLegacy(String platform) {
    if ("FACEBOOK".equalsIgnoreCase(platform) || "INSTAGRAM".equalsIgnoreCase(platform))
      throw new BusinessException(
          ErrorCode.META_OAUTH_UNAVAILABLE,
          "Use the real Meta OAuth session endpoints for Facebook/Instagram");
  }
}
