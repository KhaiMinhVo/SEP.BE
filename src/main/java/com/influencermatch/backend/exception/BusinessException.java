package com.influencermatch.backend.exception;

public class BusinessException extends RuntimeException {
  private final ErrorCode code;

  public BusinessException(ErrorCode code, String detail) {
    super(detail);
    this.code = code;
  }

  public BusinessException(ErrorCode code, String detail, Throwable cause) {
    super(detail, cause);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
