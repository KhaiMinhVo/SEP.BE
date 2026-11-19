package com.influencermatch.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Transport helpers for historical fixtures; production continues to require real sessions/CSRF.
 */
public final class TestAuthHttp {
  public static com.influencermatch.backend.auth.dto.PolicyAcceptance acceptance() {
    return new com.influencermatch.backend.auth.dto.PolicyAcceptance(
        true, "terms-v1", true, "privacy-v1");
  }

  public static RequestPostProcessor csrf() {
    return req -> {
      String raw = java.util.UUID.randomUUID().toString();
      java.util.List<Cookie> cookies = new java.util.ArrayList<>();
      if (req.getCookies() != null) cookies.addAll(java.util.Arrays.asList(req.getCookies()));
      cookies.add(new Cookie("IM_CSRF", raw));
      req.setCookies(cookies.toArray(Cookie[]::new));
      var token =
          new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", raw);
      new org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler()
          .handle(req, new org.springframework.mock.web.MockHttpServletResponse(), () -> token);
      var masked =
          (org.springframework.security.web.csrf.CsrfToken)
              req.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
      req.addHeader("X-CSRF-TOKEN", masked.getToken());
      return req;
    };
  }

  /** Existing scenario fixtures retain their token values, but send them exclusively as cookies. */
  public static RequestPostProcessor request(Object body) {
    return req -> {
      if (body instanceof java.util.Map<?, ?> map && map.get("refreshToken") instanceof String raw)
        req.setCookies(new Cookie("IM_REFRESH", raw));
      if (body instanceof String text && text.contains("refreshToken")) {
        try {
          req.setCookies(
              new Cookie(
                  "IM_REFRESH", new ObjectMapper().readTree(text).path("refreshToken").asText()));
        } catch (Exception ex) {
          throw new IllegalArgumentException(ex);
        }
      }
      return csrf().postProcessRequest(req);
    };
  }

  /** Cookie extraction into test-only fixture JSON, after asserting the HTTP JSON has no token. */
  public static JsonNode read(MockHttpServletResponse response, ObjectMapper mapper)
      throws Exception {
    JsonNode node =
        response.getContentAsByteArray().length == 0
            ? mapper.nullNode()
            : mapper.readTree(response.getContentAsByteArray());
    var cookie = response.getCookie("IM_REFRESH");
    if (cookie != null
        && !cookie.getValue().isBlank()
        && node.path("data") instanceof ObjectNode data) {
      assertThat(data.has("refreshToken")).as("Refresh must not be serialized").isFalse();
      data.put("refreshToken", cookie.getValue());
    }
    return node;
  }
}
