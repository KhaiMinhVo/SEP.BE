package com.influencermatch.backend.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.*;

/** Existing scenarios act as an ETag-aware client; strict missing-header tests use raw MockMvc. */
public final class TestBrandHttp {
  private TestBrandHttp() {}

  public static RequestPostProcessor revision(MockMvc mvc) {
    return req -> {
      String path = req.getRequestURI();
      String method = req.getMethod();
      boolean mutation =
          ("PUT".equals(method) && path.startsWith("/brands/"))
              || ("PATCH".equals(method) && "/brands/me".equals(path))
              || ("POST".equals(method) && "/brands/me/onboarding/complete".equals(path));
      if (mutation && req.getHeader("If-Match") == null && req.getHeader("Authorization") != null) {
        try {
          var response =
              mvc.perform(get("/brands/me").header("Authorization", req.getHeader("Authorization")))
                  .andReturn()
                  .getResponse();
          if (response.getStatus() == 200 && response.getHeader("ETag") != null)
            req.addHeader("If-Match", response.getHeader("ETag"));
        } catch (Exception ex) {
          throw new IllegalStateException("Could not read current Brand revision", ex);
        }
      }
      return req;
    };
  }
}
