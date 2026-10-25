package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.dto.ConnectAccountRequest;
import com.influencermatch.backend.creator.dto.ConnectAccountResponse;
import com.influencermatch.backend.creator.dto.LinkedAccountDto;
import com.influencermatch.backend.creator.service.CreatorAccountLinkService;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/creators/portal/accounts")
@RequiredArgsConstructor
@Tag(name = "Creator Portal Accounts")
@SecurityRequirement(name = "BearerAuth")
public class CreatorPortalAccountController {

  private final CreatorAccountLinkService accountLinkService;
  private final com.influencermatch.backend.creator.client.OAuthProofClient proofClient;

  @PostMapping("/connect/{platform}/start")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @Operation(summary = "Initiate connection flow for an additional social media channel")
  public ApiResponse<Map<String, String>> startConnect(
      @PathVariable("platform") String platform) {

    User actor = Permissions.actor();
    String startUri = proofClient.buildAuthorizationUrl(platform, actor.getId().toString());
    return ApiResponse.ok(Map.of(
        "platform", platform.toUpperCase(),
        "startUri", startUri,
        "message", "Redirect client to OAuth provider to authorize " + platform.toUpperCase() + " channel"
    ));
  }

  @PostMapping("/connect/{platform}/callback")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @Operation(summary = "Complete connection of additional channel using OAuth code")
  public ApiResponse<ConnectAccountResponse> handleConnectCallback(
      @PathVariable("platform") String platform,
      @Valid @RequestBody ConnectAccountRequest request) {

    User actor = Permissions.actor();
    ConnectAccountResponse response = accountLinkService.linkAdditionalAccount(
        actor.getId(), platform, request.getCode());

    return ApiResponse.ok(response);
  }

  @GetMapping
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @Operation(summary = "List all social media accounts linked to current creator's master profile")
  public ApiResponse<List<LinkedAccountDto>> getLinkedAccounts() {
    User actor = Permissions.actor();
    List<LinkedAccountDto> accounts = accountLinkService.getLinkedAccounts(actor.getId());
    return ApiResponse.ok(accounts);
  }
}
