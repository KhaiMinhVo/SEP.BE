package com.influencermatch.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.*;

class CorrelationIdFilterTest {

  @Test
  void preservesSafeClientCorrelationId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER, "client-12345");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> mdcTraceId = new AtomicReference<>();

    new CorrelationIdFilter()
        .doFilter(
            request,
            response,
            (req, res) -> {
              mdcTraceId.set(MDC.get("traceId"));
            });

    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("client-12345");
    assertThat(mdcTraceId.get()).isEqualTo("client-12345");
    assertThat(MDC.get("traceId")).isNull(); // Ensure MDC is cleaned up in finally block
  }

  @Test
  void generatesUuidWhenHeaderMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    new CorrelationIdFilter().doFilter(request, response, (req, res) -> {});

    String header = response.getHeader(CorrelationIdFilter.HEADER);
    assertThat(header).isNotBlank();
    assertThat(UUID.fromString(header)).isNotNull();
    assertThat(MDC.get("traceId")).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "short", // < 8 characters
        "a",
        "bad value\n", // Newline injection
        "trace.with.dots", // Dots not allowed
        "trace<script>", // XSS characters
        "12345678901234567890123456789012345678901234567890123456789012345", // 65 chars (> 64)
        "   ",
        "drop table users;--"
      })
  void replacesUnsafeOrMalformedCorrelationIdWithUuid(String candidate) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER, candidate);
    MockHttpServletResponse response = new MockHttpServletResponse();

    new CorrelationIdFilter().doFilter(request, response, (req, res) -> {});

    String header = response.getHeader(CorrelationIdFilter.HEADER);
    assertThat(header).isNotBlank();
    assertThat(header).isNotEqualTo(candidate);
    assertThat(UUID.fromString(header)).isNotNull();
    assertThat(MDC.get("traceId")).isNull();
  }
}
