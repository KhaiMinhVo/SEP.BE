package com.influencermatch.backend.auth.controller;

import com.influencermatch.backend.auth.dto.CreatorRegistrationDtos.*;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Tag(name="Creator Registration")
public class CreatorRegistrationController {
  private final CreatorRegistrationInvitationService invitations;
  private final PasswordSetupService passwords;
  @PostMapping("/data/creator-invitations")
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('INVITE_CREATOR')")
  @Operation(summary="Send or replace a Creator registration invitation") @SecurityRequirement(name="BearerAuth")
  public ResponseEntity<ApiResponse<InvitationResponse>> invite(@Valid @RequestBody InvitationRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Invitation email sent.",invitations.invite(request)));
  }
  @PostMapping("/auth/creator-invitations/resolve") @Operation(summary="Resolve an invitation without consuming it",security={})
  public ApiResponse<InvitationResponse> resolve(@Valid @RequestBody ResolveRequest request) {return ApiResponse.ok(invitations.resolve(request.token()));}
  @PostMapping("/auth/creator/register") @Operation(summary="Register a Creator using a valid invitation and send verification OTP",security={})
  public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterCreatorRequest request) {
    invitations.register(request);return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Account created; verification email sent.",null));
  }
  @PostMapping("/auth/password-setup/request") @Operation(summary="Send OTP to set the first password for a legacy Google account",security={})
  public ApiResponse<Void> requestPassword(@Valid @RequestBody PasswordSetupRequest request) {passwords.request(request.email());return ApiResponse.ok("If eligible, a password setup email has been sent.",null);}
  @PostMapping("/auth/password-setup/confirm") @Operation(summary="Set the first password using its dedicated OTP; no session is issued",security={})
  public ApiResponse<Void> confirmPassword(@Valid @RequestBody PasswordSetupConfirm request) {passwords.confirm(request);return ApiResponse.empty();}
}
