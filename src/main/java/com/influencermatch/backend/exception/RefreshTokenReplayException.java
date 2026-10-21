package com.influencermatch.backend.exception;

/** Rejects refresh-token replay after committing the defensive session revocation. */
public final class RefreshTokenReplayException extends BusinessException {
  public RefreshTokenReplayException() {
    super(ErrorCode.UNAUTHENTICATED, "Refresh token is invalid or expired");
  }
}
