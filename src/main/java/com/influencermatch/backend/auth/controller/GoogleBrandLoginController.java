package com.influencermatch.backend.auth.controller;

import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.SecureRandom;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/google")
@RequiredArgsConstructor
@Tag(name = "Google Brand Login")
public class GoogleBrandLoginController {
  private final GoogleBrandLoginService login;
  private final GoogleIdTokenVerifier verifier;

  public record LoginRequest(
      @NotNull UUID sessionId, @NotBlank @Size(max = 16384) String credential) {}

  @PostMapping("/start")
  @Operation(
      summary = "Start Brand Google Login; pass nonce to Google Identity Services",
      security = {})
  public ResponseEntity<ApiResponse<GoogleBrandLoginService.Start>> start(
      HttpServletRequest request) {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String binding = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var result = login.start(binding);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            cookie(result.sessionId(), binding, request.isSecure(), 600).toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(ApiResponse.ok(result));
  }

  @PostMapping("/login")
  @Operation(
      summary = "Verify GIS credential and issue Brand session; original browser cookie required",
      security = {})
  public ResponseEntity<ApiResponse<LoginResponse>> login(
      @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
    String binding = null;
    if (request.getCookies() != null)
      for (Cookie cookie : request.getCookies())
        if (cookie.getName().equals(cookieName(body.sessionId()))) binding = cookie.getValue();
    var proof = verifier.verify(body.credential());
    var result = login.finish(body.sessionId(), binding, proof);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE, cookie(body.sessionId(), "", request.isSecure(), 0).toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(ApiResponse.ok(result));
  }

  private String cookieName(UUID id) {
    return "google_login_" + id;
  }

  private ResponseCookie cookie(UUID id, String value, boolean secure, long age) {
    return ResponseCookie.from(cookieName(id), value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/api/v1/auth/google")
        .maxAge(age)
        .build();
  }
}
