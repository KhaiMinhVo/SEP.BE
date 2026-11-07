package com.influencermatch.backend.exception;

public class ProfileIncompleteException extends BusinessException {
  private final java.util.Map<String, java.util.List<String>> fields;

  public ProfileIncompleteException(java.util.Map<String, java.util.List<String>> fields) {
    super(ErrorCode.VALIDATION_ERROR, "Required onboarding fields are missing");
    this.fields = fields;
  }

  public java.util.Map<String, java.util.List<String>> fields() {
    return fields;
  }
}
