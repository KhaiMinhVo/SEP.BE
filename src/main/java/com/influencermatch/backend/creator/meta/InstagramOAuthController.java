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
@Tag(name = "Instagram Direct Verification")
public class InstagramOAuthController {
  private final MetaSessionStore store;
  private final MetaOAuthService oauth;
  private final InstagramOAuthProperties config;
  private final SecureRandom random = new SecureRandom();

  @PostMapping("/creators/portal/accounts/connect/instagram/start")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @Operation(
      summary = "Connect an Instagram Professional account without Facebook",
      description =
          "Email-verified Creator only. Start in the browser used for consent; private session and"
              + " confirmation use existing Meta session endpoints. Does not sign in/register a"
              + " User or grant ownership.")
  public ResponseEntity<ApiResponse<MetaDtos.Started>> start(
      @Valid @RequestBody MetaDtos.InstagramStart request) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String binding = HexFormat.of().formatHex(bytes);
    var result = store.startInstagram(request, binding);
    var redirect = URI.create(config.redirectUri());
    var cookie =
        ResponseCookie.from(MetaSessionStore.cookieName(result.sessionId()), binding)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path(redirect.getPath())
            .maxAge(600)
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(ApiResponse.ok(result));
  }

  @GetMapping("/integrations/instagram/callback")
  @Operation(
      summary = "Public Instagram Login callback",
      description =
          "One-time state, matching flow and original browser cookie required. Returns session"
              + " ID/status only; no token, account choices or ownership grant.",
      security = {})
  public ApiResponse<MetaDtos.Callback> callback(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error,
      HttpServletRequest request) {
    var cookies = new HashMap<String, String>();
    if (request.getCookies() != null)
      for (var cookie : request.getCookies()) cookies.put(cookie.getName(), cookie.getValue());
    return ApiResponse.ok(oauth.instagramCallback(state, code, error, cookies));
  }
}
