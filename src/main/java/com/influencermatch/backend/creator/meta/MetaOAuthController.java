package com.influencermatch.backend.creator.meta;

import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "Meta Account Verification")
public class MetaOAuthController {
  private final MetaSessionStore store;
  private final MetaOAuthService oauth;
  private final MetaOAuthProperties config;
  private final java.security.SecureRandom random = new java.security.SecureRandom();

  @PostMapping("/creators/portal/accounts/connect/meta/start")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @SecurityRequirement(name = "BearerAuth")
  @Operation(
      summary = "Start real Meta OAuth for an existing Page or Instagram Professional account",
      description =
          "Verified Creator only; ten-minute session. Call in browser Swagger/frontend to set the"
              + " HttpOnly callback binding cookie, then open authorizationUrl in that same"
              + " browser. Does not sign in/register users or grant ownership. No mock fallback.")
  public org.springframework.http.ResponseEntity<ApiResponse<MetaDtos.Started>> start(
      @Valid @RequestBody MetaDtos.Start request) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String binding = java.util.HexFormat.of().formatHex(bytes);
    var response = store.start(request, binding);
    var redirect = java.net.URI.create(config.redirectUri());
    var cookie =
        org.springframework.http.ResponseCookie.from(
                MetaSessionStore.cookieName(response.sessionId()), binding)
            .httpOnly(true)
            .secure("https".equals(redirect.getScheme()))
            .sameSite("Lax")
            .path(redirect.getPath())
            .maxAge(600)
            .build();
    return org.springframework.http.ResponseEntity.ok()
        .header(org.springframework.http.HttpHeaders.SET_COOKIE, cookie.toString())
        .body(ApiResponse.ok(response));
  }

  @GetMapping("/integrations/meta/callback")
  @Operation(
      summary = "Public Meta callback",
      description =
          "One-time state and original browser HttpOnly cookie required; returns session ID/status"
              + " only. Account choices and tokens are never returned here.",
      security = {})
  public ApiResponse<MetaDtos.Callback> callback(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error,
      jakarta.servlet.http.HttpServletRequest request) {
    var cookies = new java.util.HashMap<String, String>();
    if (request.getCookies() != null)
      for (var cookie : request.getCookies()) cookies.put(cookie.getName(), cookie.getValue());
    return ApiResponse.ok(oauth.callback(state, code, error, cookies));
  }

  @GetMapping("/creators/portal/accounts/connect/meta/sessions/{id}")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @SecurityRequirement(name = "BearerAuth")
  @Operation(
      summary = "Read my sanitized Meta account choices",
      description =
          "Other Creators get 404. UNMATCHED accounts require manual Claim; no username/email"
              + " matching.")
  public ApiResponse<MetaDtos.Session> session(@PathVariable UUID id) {
    return ApiResponse.ok(store.view(id));
  }

  @PostMapping("/creators/portal/accounts/connect/meta/sessions/{id}/confirm")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @SecurityRequirement(name = "BearerAuth")
  @Operation(
      summary = "Confirm one existing Meta channel",
      description =
          "Rechecks Meta outside DB locks, then checks User/permission, invitations and ownership"
              + " under locks. Exact stable IDs only; 409 on conflict/replay; tokens discarded"
              + " after success.")
  public ApiResponse<MetaDtos.Connected> confirm(
      @PathVariable UUID id, @Valid @RequestBody MetaDtos.Confirm request) {
    return ApiResponse.ok(oauth.confirm(id, request.accountId()));
  }
}
