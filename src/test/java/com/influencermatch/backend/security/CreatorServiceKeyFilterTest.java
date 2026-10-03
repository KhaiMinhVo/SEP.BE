package com.influencermatch.backend.security;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;

class CreatorServiceKeyFilterTest {
  @Test
  void missingConfigurationIs503() throws Exception {
    reject("", null, 503);
  }

  @Test
  void missingOrWrongKeyAndJwtAloneAre401() throws Exception {
    reject("test-key", null, 401);
    reject("test-key", "wrong-key", 401);
  }

  private void reject(String configured, String supplied, int status) throws Exception {
    var request = new MockHttpServletRequest("POST", "/creators/ingest");
    request.addHeader("Authorization", "Bearer user-jwt");
    if (supplied != null) request.addHeader("X-Service-Key", supplied);
    var response = new MockHttpServletResponse();
    var called = new AtomicBoolean();
    new CreatorServiceKeyFilter(configured, new ObjectMapper())
        .doFilter(request, response, (a, b) -> called.set(true));
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(called).isFalse();
    assertThat(response.getContentAsString()).doesNotContain("test-key", "wrong-key", "user-jwt");
  }

  @Test
  void correctKeyGrantsOnlyIngestionAndDoesNotPersistContext() throws Exception {
    var request = new MockHttpServletRequest("POST", "/creators/ingest");
    request.addHeader("X-Service-Key", "test-key");
    new CreatorServiceKeyFilter("test-key", new ObjectMapper())
        .doFilter(
            request,
            new MockHttpServletResponse(),
            (a, b) -> {
              assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                  .extracting("authority")
                  .containsExactly("SERVICE_INGEST_CREATOR");
            });
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
