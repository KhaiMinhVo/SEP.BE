package com.influencermatch.backend.exception;

public class NotFoundException extends BusinessException {
  public NotFoundException(String detail) {
    super(ErrorCode.RESOURCE_NOT_FOUND, detail);
  }
}
