package com.influencermatch.backend.auth.controller;

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
  private final AuthCookies authCookies;

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
      summary =
          "Verify GIS credential: AUTHENTICATED or REGISTRATION_REQUIRED; browser cookie and CSRF"
              + " required",
      security = {})
  public ResponseEntity<ApiResponse<GoogleBrandLoginService.Result>> login(
      @Valid @RequestBody LoginRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    String binding = null;
    if (request.getCookies() != null)
      for (Cookie cookie : request.getCookies())
        if (cookie.getName().equals(cookieName(body.sessionId()))) binding = cookie.getValue();
    var proof = verifier.verify(body.credential());
    var result = login.finish(body.sessionId(), binding, proof);
    if (result.authentication() != null) authCookies.write(response, result.authentication());
    if (result.authentication() != null)
      response.addHeader(
          HttpHeaders.SET_COOKIE, cookie(body.sessionId(), "", request.isSecure(), 0).toString());
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(ApiResponse.ok(result));
  }

  @PostMapping("/registration/complete")
  @Operation(
      summary = "Complete new Google Brand registration before issuing a session",
      security = {})
  public ApiResponse<GoogleBrandLoginService.Result> complete(
      @Valid @RequestBody GoogleBrandLoginService.Complete body,
      HttpServletRequest request,
      HttpServletResponse response) {
    var result = login.complete(body, binding(request, body.sessionId()));
    authCookies.write(response, result.authentication());
    response.addHeader(
        HttpHeaders.SET_COOKIE, cookie(body.sessionId(), "", request.isSecure(), 0).toString());
    return ApiResponse.ok(result);
  }

  public record LinkStart(@NotBlank @Size(max = 256) String password) {}

  @PostMapping("/link/start")
  @org.springframework.security.access.prepost.PreAuthorize("hasRole('BRAND')")
  @Operation(summary = "Reauthenticate Brand password and start a Google linking challenge")
  public ResponseEntity<ApiResponse<GoogleBrandLoginService.Start>> linkStart(
      @Valid @RequestBody LinkStart body, HttpServletRequest request) {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String binding = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var result = login.startLink(body.password(), binding);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            cookie(result.sessionId(), binding, request.isSecure(), 600).toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(ApiResponse.ok(result));
  }

  @PostMapping("/link/confirm")
  @org.springframework.security.access.prepost.PreAuthorize("hasRole('BRAND')")
  @Operation(summary = "Link verified Google identity to the reauthenticated Brand")
  public ApiResponse<Void> linkConfirm(
      @Valid @RequestBody LoginRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    var proof = verifier.verify(body.credential());
    login.confirmLink(body.sessionId(), binding(request, body.sessionId()), proof);
    response.addHeader(
        HttpHeaders.SET_COOKIE, cookie(body.sessionId(), "", request.isSecure(), 0).toString());
    return ApiResponse.empty();
  }

  private String binding(HttpServletRequest request, UUID id) {
    if (request.getCookies() != null)
      for (var c : request.getCookies())
        if (c.getName().equals(cookieName(id))) return c.getValue();
    return null;
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
