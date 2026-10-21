package com.influencermatch.backend.creator.controller;
import com.influencermatch.backend.common.dto.*;
import com.influencermatch.backend.creator.dto.CreatorClaimDtos.*;
import com.influencermatch.backend.creator.enums.CreatorClaimStatus;
import com.influencermatch.backend.creator.service.CreatorClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/data/creator-claims") @RequiredArgsConstructor
@Tag(name="Creator Claim Review",description="Admin/Data Manager; private evidence and audited decisions")
@SecurityRequirement(name="BearerAuth")
@PreAuthorize("hasAuthority('REVIEW_CREATOR_CLAIM')")
public class CreatorClaimReviewController {
  private final CreatorClaimService service;
  @GetMapping @Operation(summary="List claims for review")
  public ApiResponse<PageResponse<ClaimResponse>> list(@RequestParam(required=false) CreatorClaimStatus status,
      @RequestParam(required=false) UUID creatorId,@RequestParam(required=false) UUID claimantId,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    return ApiResponse.ok(service.pending(status,creatorId,claimantId,page,size));
  }
  @GetMapping("/{id}") @Operation(summary="Read private claim evidence")
  public ApiResponse<ClaimResponse> detail(@PathVariable UUID id) {return ApiResponse.ok(service.detail(id));}
  @PatchMapping("/{id}/review") @Operation(summary="Approve or reject a pending claim",description="Reason required. Approval verifies ownership and rejects competing pending claims atomically.")
  public ApiResponse<ClaimResponse> review(@PathVariable UUID id,@Valid @RequestBody ReviewRequest request) {return ApiResponse.ok(service.review(id,request));}
  @PostMapping("/{id}/revoke") @Operation(summary="Revoke approved ownership",description="Reason required. Clears owner and authorization; hides rates while retaining history.")
  public ApiResponse<ClaimResponse> revoke(@PathVariable UUID id,@Valid @RequestBody RevokeRequest request) {return ApiResponse.ok(service.revoke(id,request));}
}
