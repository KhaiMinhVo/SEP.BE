package com.influencermatch.backend.dto;
import org.junit.jupiter.api.Test; import org.slf4j.MDC; import static org.assertj.core.api.Assertions.assertThat;
class ApiResponseTest {
 @Test void includesTraceIdFromMdc(){MDC.put("traceId","trace-123");try{ApiResponse<String> response=ApiResponse.ok("payload");assertThat(response.success()).isTrue();assertThat(response.data()).isEqualTo("payload");assertThat(response.meta().traceId()).isEqualTo("trace-123");assertThat(response.meta().timestamp()).isNotNull();}finally{MDC.clear();}}
}
