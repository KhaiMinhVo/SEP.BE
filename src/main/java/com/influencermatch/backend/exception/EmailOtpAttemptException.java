package com.influencermatch.backend.exception;

/** Failed verification must commit its attempt counter, while still returning an API error. */
public class EmailOtpAttemptException extends BusinessException {
  public EmailOtpAttemptException(ErrorCode code) { super(code, code.title()); }
}
