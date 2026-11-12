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
  private final com.influencermatch.backend.creator.email.ClaimEmailOutboxService outbox;

  @PostMapping("/{id}/invite")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('MANAGE_OWN_SHORTLIST')")
  @SecurityRequirement(name = "BearerAuth")
  @Operation(summary = "Queue a seven-day claim invitation email", description="BRAND owning the profile/campaign only. Commits invitation and encrypted outbox before async SMTP. HTTP 200 means queued, not delivered. Blocks owned channels or effective pending invitations with 409. No raw token; this link does not register a Creator.")
  public ApiResponse<InvitationResponse> sendInvitation(
      @PathVariable("id") UUID creatorId,
      @Valid @RequestBody SendInvitationRequest request) {
    InvitationResponse response =
        invitationService.sendInvitation(creatorId, request.getEmail(), null, request.getCampaignId());
    return ApiResponse.ok(response);
  }

  @PostMapping("/invitations")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('MANAGE_OWN_SHORTLIST')")
  @SecurityRequirement(name = "BearerAuth")
  @Operation(summary = "Send claim invitation to creator (collection endpoint)")
  public ApiResponse<InvitationResponse> sendInvitationCollection(
      @Valid @RequestBody SendInvitationRequest request) {
    if (request.getCreatorId() == null) {
      throw new com.influencermatch.backend.exception.BusinessException(
          com.influencermatch.backend.exception.ErrorCode.MALFORMED_REQUEST, "creatorId is required");
    }
    InvitationResponse response =
        invitationService.sendInvitation(request.getCreatorId(), request.getEmail(), null, request.getCampaignId());
    return ApiResponse.ok(response);
  }

  @GetMapping("/invitations/{id}/email-delivery")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('MANAGE_OWN_SHORTLIST')")
  @SecurityRequirement(name="BearerAuth")
  @Operation(summary="Read claim email delivery status",description="Only the owning Brand. Historical invitations return NOT_TRACKED. No email content, token or SMTP errors are exposed.")
  public ApiResponse<com.influencermatch.backend.creator.email.ClaimEmailOutboxService.Delivery> delivery(@PathVariable UUID id) {
    return ApiResponse.ok(outbox.status(id));
  }

  @PostMapping("/invitations/{id}/retry-email")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('MANAGE_OWN_SHORTLIST')")
  @SecurityRequirement(name="BearerAuth")
  @Operation(summary="Queue a failed claim email again",description="FAILED and still valid only. Preserves token/email/expiry. At least 60 seconds since the last attempt; at most five manual retries per hour. 409 if not retryable; 429 if rate limited.")
  public ApiResponse<com.influencermatch.backend.creator.email.ClaimEmailOutboxService.Delivery> retry(@PathVariable UUID id) {
    return ApiResponse.ok(outbox.retry(id));
  }

  @GetMapping("/claim/invitation/verify")
  @Operation(summary = "Verify creator claim invitation token (Public)")
  public ApiResponse<VerifyTokenResponse> verifyInvitation(
      @RequestParam("token") String token) {
    VerifyTokenResponse response = invitationService.verifyInvitationToken(token);
    return ApiResponse.ok(response);
  }
}
