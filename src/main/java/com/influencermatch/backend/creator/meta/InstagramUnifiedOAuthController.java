package com.influencermatch.backend.creator.meta;

import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.SecureRandom;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "Instagram Unified Verification")
public class InstagramUnifiedOAuthController {
  private final MetaSessionStore store;
  private final MetaOAuthService oauth;
  private final InstagramUnifiedOAuthProperties config;
  private final SecureRandom random = new SecureRandom();

  @PostMapping("/creators/portal/accounts/connect/instagram-unified/start")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @Operation(
      summary = "Start Instagram Unified Login using Facebook Login for Business",
      description =
          "Creates a Creator-bound, browser-bound, short-lived OAuth session. Requires a Business"
              + " Portfolio and an Instagram asset granted to the configuration. Does not grant"
              + " ownership or sign in to InfluencerMatch.")
  public ResponseEntity<ApiResponse<MetaDtos.Started>> start(
      @Valid @RequestBody MetaDtos.InstagramStart request) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String binding = HexFormat.of().formatHex(bytes);
    var result = store.startInstagramUnified(request, binding);
    var redirect = URI.create(config.redirectUri());
    var cookie =
        ResponseCookie.from(MetaSessionStore.cookieName(result.sessionId()), binding)
            .httpOnly(true)
            .secure("https".equals(redirect.getScheme()))
            .sameSite("Lax")
            .path(redirect.getPath())
            .maxAge(600)
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(ApiResponse.ok(result));
  }

  @GetMapping("/integrations/instagram-unified/callback")
  @Operation(
      summary = "Public Instagram Unified Login callback",
      description =
          "Exchanges a one-time authorization code only for the matching state and original"
              + " browser cookie. Returns session status only; tokens are not returned.",
      security = {})
  public ApiResponse<MetaDtos.Callback> callback(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error,
      HttpServletRequest request) {
    var cookies = new HashMap<String, String>();
    if (request.getCookies() != null)
      for (var cookie : request.getCookies()) cookies.put(cookie.getName(), cookie.getValue());
    return ApiResponse.ok(oauth.instagramUnifiedCallback(state, code, error, cookies));
  }
}
