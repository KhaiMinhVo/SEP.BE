package com.influencermatch.backend.exception;
public class ConflictException extends BusinessException { public ConflictException(ErrorCode code,String detail){super(code,detail);if(!code.status().is4xxClientError())throw new IllegalArgumentException("Conflict code required");} }


