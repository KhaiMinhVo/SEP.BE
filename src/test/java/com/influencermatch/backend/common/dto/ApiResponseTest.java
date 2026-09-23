package com.influencermatch.backend.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.influencermatch.backend.common.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ApiResponseTest {

  @Test
  @DisplayName("API Response - Should include TraceId from MDC in meta context")
  void includesTraceIdFromMdc() {
    // GIVEN
    MDC.put("traceId", "trace-123");
    
    try {
      // WHEN
      ApiResponse<String> response = ApiResponse.ok("payload");
      
      // THEN
      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo("payload");
      assertThat(response.meta().traceId()).isEqualTo("trace-123");
      assertThat(response.meta().timestamp()).isNotNull();
    } finally {
      // CLEANUP
      MDC.clear();
    }
  }
}
