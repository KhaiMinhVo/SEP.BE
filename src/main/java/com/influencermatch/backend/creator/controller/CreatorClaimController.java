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
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @Tag(name="Creator Claims")
@SecurityRequirement(name="BearerAuth")
public class CreatorClaimController {
  private final CreatorClaimService service;
  @PostMapping("/creators/{id}/claim") @PreAuthorize("hasAuthority('CLAIM_CREATOR_PROFILE')")
  @Operation(summary="Submit channel ownership evidence",description="Creates PENDING claim, not authorization. Requires an active CREATOR with verified email. Evidence is private and URLs are not fetched.")
  public ResponseEntity<ApiResponse<ClaimResponse>> claim(@PathVariable UUID id,@Valid @RequestBody ClaimRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.claim(id,request)));
  }
  @GetMapping("/creator-claims/me") @PreAuthorize("hasAuthority('VIEW_OWN_CREATOR_CLAIM')")
  @Operation(summary="Read my claims")
  public ApiResponse<PageResponse<ClaimResponse>> mine(@RequestParam(required=false) CreatorClaimStatus status,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    return ApiResponse.ok(service.mine(status,page,size));
  }
}
