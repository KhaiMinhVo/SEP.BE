package com.influencermatch.backend.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Explicit ETag-aware test client. Contract rejection tests use raw MockMvc. */
public final class TestCampaignHttp {
  public static RequestPostProcessor revision(MockMvc mvc) {
    return req -> {
      String path = req.getRequestURI();
      if (path.matches("/campaigns/[0-9a-fA-F-]{36}(/status|/archive)?")
          && java.util.Set.of("PUT", "PATCH").contains(req.getMethod())
          && req.getHeader("If-Match") == null
          && req.getHeader("Authorization") != null) {
        try {
          String id = path.split("/")[2];
          var r =
              mvc.perform(
                      get("/campaigns/" + id)
                          .header("Authorization", req.getHeader("Authorization")))
                  .andReturn()
                  .getResponse();
          if (r.getHeader("ETag") != null) req.addHeader("If-Match", r.getHeader("ETag"));
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
      return req;
    };
  }
}
