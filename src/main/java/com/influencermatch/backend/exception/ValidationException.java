package com.influencermatch.backend.exception;
public class ValidationException extends BusinessException { public ValidationException(String detail){super(ErrorCode.VALIDATION_ERROR,detail);} }


