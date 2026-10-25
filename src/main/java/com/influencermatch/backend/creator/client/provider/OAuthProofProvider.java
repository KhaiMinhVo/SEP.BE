package com.influencermatch.backend.creator.client.provider;

import com.influencermatch.backend.creator.client.OAuthProofClient.ProofResult;

public interface OAuthProofProvider {

  /**
   * Platform identifier (TIKTOK, YOUTUBE, GOOGLE, etc.)
   */
  String getPlatform();

  /**
   * Checks whether developer sandbox credentials are configured for this provider.
   */
  boolean isConfigured();

  /**
   * Builds the official authorization URL for the provider consent/login screen.
   *
   * @param state State payload (e.g. encrypted or hash-linked token) to preserve through callback
   * @return Absolute authorization redirect URL
   */
  String buildAuthorizationUrl(String state);

  /**
   * Exchanges authorization code for tokens and queries user/channel profile to verify identity.
   *
   * @param code The authorization code returned by the provider
   * @param expectedExternalId Expected platform account ID from crawler (e.g. YouTube Channel ID)
   * @param expectedUserName Expected platform username/handle (e.g. TikTok @handle)
   * @return Verified ProofResult with tokens and platform identifiers
   */
  ProofResult exchangeAndVerify(String code, String expectedExternalId, String expectedUserName);
}
