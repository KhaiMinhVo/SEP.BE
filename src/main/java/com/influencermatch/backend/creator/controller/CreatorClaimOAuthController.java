package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.dto.OAuthCallbackResponse;
import com.influencermatch.backend.creator.service.CreatorClaimResolutionService;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/creators/claim/oauth")
@RequiredArgsConstructor
@Tag(name = "Creator Claim OAuth")
public class CreatorClaimOAuthController {

  private final CreatorClaimResolutionService claimResolutionService;
  private final CreatorInvitationService invitationService;

  @GetMapping("/{platform}/start")
  @Operation(summary = "Start OAuth Proof of Control flow (Public)")
  public ResponseEntity<Void> startOAuth(
      @PathVariable("platform") String platform,
      @RequestParam("token") String token,
      @RequestParam(value = "redirect", defaultValue = "true") boolean redirect) {

    // First ensure token is valid before initiating redirect
    invitationService.verifyInvitationToken(token);

    // Build platform authorization URL carrying raw token in state
    String encodedState = URLEncoder.encode(token, StandardCharsets.UTF_8);
    String redirectUri = "/api/v1/creators/claim/oauth/" + platform.toLowerCase() + "/callback?code=sim-auth-code&state=" + encodedState;

    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, redirectUri)
        .build();
  }

  @GetMapping("/{platform}/callback")
  @Operation(summary = "OAuth callback for profile claim proof of control (Public GET redirect)")
  public ApiResponse<OAuthCallbackResponse> handleOAuthCallback(
      @PathVariable("platform") String platform,
      @RequestParam("code") String code,
      @RequestParam("state") String state) {

    OAuthCallbackResponse response =
        claimResolutionService.resolveClaim(platform, code, state, com.influencermatch.backend.security.permission.Permissions.actorOrNull());

    return ApiResponse.ok(response);
  }

  @PostMapping("/{platform}/callback")
  @Operation(summary = "OAuth callback for profile claim proof of control (JSON POST)")
  public ApiResponse<OAuthCallbackResponse> handleOAuthCallbackPost(
      @PathVariable("platform") String platform,
      @RequestBody java.util.Map<String, String> body) {

    String code = body.getOrDefault("code", body.get("authorizationCode"));
    String state = body.getOrDefault("token", body.get("state"));
    OAuthCallbackResponse response =
        claimResolutionService.resolveClaim(platform, code, state, com.influencermatch.backend.security.permission.Permissions.actorOrNull());

    return ApiResponse.ok(response);
  }
}
