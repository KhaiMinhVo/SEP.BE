package com.influencermatch.backend.common.dto;

import java.time.Instant;
import org.slf4j.MDC;

public record ApiResponse<T>(boolean success, T data, ResponseMeta meta) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, data, metadata());
  }

  public static <T> ApiResponse<T> ok(String ignoredMessage, T data) {
    return ok(data);
  }

  public static ApiResponse<Void> empty() {
    return new ApiResponse<>(true, null, metadata());
  }

  private static ResponseMeta metadata() {
    return new ResponseMeta(Instant.now(), MDC.get("traceId"));
  }

  public record ResponseMeta(Instant timestamp, String traceId) {}
}
