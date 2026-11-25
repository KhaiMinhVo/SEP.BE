package com.influencermatch.backend.creator.portal;

import com.influencermatch.backend.creator.meta.OAuthFlow;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("application.channel-oauth")
public record ChannelOAuthAvailability(
    boolean facebookPageEnabled, InstagramMode instagramMode, boolean tiktokSandboxEnabled) {
  public enum InstagramMode {
    OFF(null),
    DIRECT(OAuthFlow.INSTAGRAM_LOGIN),
    UNIFIED(OAuthFlow.INSTAGRAM_UNIFIED_LOGIN),
    VIA_FACEBOOK_PAGE(OAuthFlow.FACEBOOK_LOGIN);

    private final OAuthFlow flow;

    InstagramMode(OAuthFlow flow) {
      this.flow = flow;
    }
  }

  public OAuthFlow instagramFlow() {
    return instagramMode == null ? null : instagramMode.flow;
  }

  public void requireMetaStart(String platform, OAuthFlow flow) {
    boolean enabled = switch (platform) {
      case "FACEBOOK" -> facebookPageEnabled && flow == OAuthFlow.FACEBOOK_LOGIN;
      case "INSTAGRAM" -> instagramFlow() == flow;
      default -> false;
    };
    if (!enabled) {
      throw new BusinessException(
          ErrorCode.META_OAUTH_UNAVAILABLE, "This Meta verification method is not enabled in this environment");
    }
  }

  public void requireTikTokStart() {
    if (!tiktokSandboxEnabled) {
      throw new BusinessException(
          ErrorCode.TIKTOK_OAUTH_UNAVAILABLE, "TikTok Sandbox is not enabled in this environment");
    }
  }
}
