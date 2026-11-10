package com.influencermatch.backend.creator.tiktok;

import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.*;
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
@Tag(name = "TikTok Sandbox Verification")
public class TikTokOAuthController {
  private final TikTokSessionStore store;
  private final TikTokOAuthService oauth;
  private final TikTokLoginProperties config;
  private final SecureRandom random = new SecureRandom();

  @PostMapping("/creators/portal/accounts/connect/tiktok/start")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @SecurityRequirement(name = "BearerAuth")
  @Operation(
      summary = "Start TikTok Sandbox OAuth; crawler author ID identifies or creates the channel")
  public ApiResponse<TikTokDtos.Started> start(
      @Valid @RequestBody TikTokDtos.Start request, HttpServletResponse response) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String binding = HexFormat.of().formatHex(bytes);
    var result = store.start(request, binding);
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(TikTokSessionStore.cookieName(result.sessionId()), binding)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path(URI.create(config.redirectUri()).getPath())
            .maxAge(600)
            .build()
            .toString());
    return ApiResponse.ok(result);
  }

  @GetMapping("/integrations/tiktok/callback")
  @Operation(
      summary =
          "TikTok callback: resolve crawler ID and authorize; READY indicates target mismatch",
      security = {})
  public ApiResponse<TikTokDtos.Callback> callback(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error,
      HttpServletRequest request) {
    Map<String, String> cookies = new HashMap<>();
    if (request.getCookies() != null)
      for (var cookie : request.getCookies()) cookies.put(cookie.getName(), cookie.getValue());
    return ApiResponse.ok(oauth.callback(state, code, error, cookies));
  }

  @GetMapping("/creators/portal/accounts/connect/tiktok/sessions/{id}")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @SecurityRequirement(name = "BearerAuth")
  @Operation(summary = "View own TikTok session and stable ID ownership result")
  public ApiResponse<TikTokDtos.Session> view(@PathVariable UUID id) {
    return ApiResponse.ok(store.view(id).unwrap());
  }
}
