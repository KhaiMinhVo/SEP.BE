package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.dto.LoginResponse;
import jakarta.servlet.http.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
  @org.springframework.beans.factory.annotation.Autowired private Clock clock = Clock.systemUTC();
  public static final String REFRESH = "IM_REFRESH";

  @Value("${application.auth.cookie-secure:false}")
  private boolean secure;

  public boolean secure() {
    return secure;
  }

  public String read(HttpServletRequest request) {
    if (request.getCookies() != null)
      for (var c : request.getCookies()) if (REFRESH.equals(c.getName())) return c.getValue();
    return null;
  }

  public void write(HttpServletResponse response, LoginResponse result) {
    long age =
        Math.max(0, Duration.between(clock.instant(), result.getRefreshExpiresAt()).getSeconds());
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.getRefreshToken(), age).toString());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  public void clear(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
  }

  private ResponseCookie cookie(String value, long age) {
    return ResponseCookie.from(REFRESH, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/api/v1/auth")
        .maxAge(age)
        .build();
  }
}
