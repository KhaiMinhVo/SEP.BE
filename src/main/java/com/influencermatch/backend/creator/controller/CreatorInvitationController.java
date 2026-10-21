package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.dto.InvitationResponse;
import com.influencermatch.backend.creator.dto.SendInvitationRequest;
import com.influencermatch.backend.creator.dto.VerifyTokenResponse;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/creators")
@RequiredArgsConstructor
@Tag(name = "Creator Invitation")
public class CreatorInvitationController {

  private final CreatorInvitationService invitationService;

  @PostMapping("/{id}/invite")
  @PreAuthorize("hasAuthority('MANAGE_OWN_SHORTLIST')")
  @SecurityRequirement(name = "BearerAuth")
  @Operation(summary = "Send claim invitation to creator")
  public ApiResponse<InvitationResponse> sendInvitation(
      @PathVariable("id") UUID creatorId,
      @Valid @RequestBody SendInvitationRequest request) {
    InvitationResponse response =
        invitationService.sendInvitation(creatorId, request.getEmail(), null, request.getCampaignId());
    return ApiResponse.ok(response);
  }

  @GetMapping("/claim/invitation/verify")
  @Operation(summary = "Verify creator claim invitation token (Public)")
  public ApiResponse<VerifyTokenResponse> verifyInvitation(
      @RequestParam("token") String token) {
    VerifyTokenResponse response = invitationService.verifyInvitationToken(token);
    return ApiResponse.ok(response);
  }
}
