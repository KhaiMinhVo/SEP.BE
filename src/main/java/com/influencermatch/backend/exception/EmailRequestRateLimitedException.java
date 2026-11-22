package com.influencermatch.backend.exception;

public class EmailRequestRateLimitedException extends BusinessException {
  private final long retryAfterSeconds;
  public EmailRequestRateLimitedException(long retryAfterSeconds) {
    super(ErrorCode.EMAIL_OTP_RATE_LIMITED, "Please wait before requesting another email");
    this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
  }
  public long retryAfterSeconds() { return retryAfterSeconds; }
}
