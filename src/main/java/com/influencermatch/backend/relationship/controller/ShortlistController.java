package com.influencermatch.backend.relationship.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.relationship.dto.AddShortlistItemRequest;
import com.influencermatch.backend.relationship.dto.ShortlistItemDto;
import com.influencermatch.backend.relationship.service.ShortlistService;
import com.influencermatch.backend.user.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/campaigns/{campaignId}/shortlist")
@RequiredArgsConstructor
@Tag(name = "Shortlist Management")
public class ShortlistController {

    private final ShortlistService shortlistService;

    @Operation(summary = "Add a creator to campaign shortlist", security = @SecurityRequirement(name = "BearerAuth"))
    @PostMapping
    @PreAuthorize("hasAuthority('UPDATE_CAMPAIGN')")
    public ResponseEntity<ApiResponse<ShortlistItemDto>> addCreatorToShortlist(
            @PathVariable UUID campaignId,
            @Valid @RequestBody AddShortlistItemRequest request,
            @AuthenticationPrincipal User currentUser) {
        ShortlistItemDto dto = shortlistService.addCreatorToShortlist(campaignId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.ok("Creator added to shortlist.", dto));
    }

    @Operation(summary = "Get shortlist for a campaign", security = @SecurityRequirement(name = "BearerAuth"))
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_OWN_CAMPAIGN')")
    public ResponseEntity<ApiResponse<Page<ShortlistItemDto>>> getShortlist(
            @PathVariable UUID campaignId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ShortlistItemDto> result = shortlistService.getShortlistByCampaign(campaignId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @Operation(summary = "Remove a creator from shortlist", security = @SecurityRequirement(name = "BearerAuth"))
    @DeleteMapping("/{shortlistItemId}")
    @PreAuthorize("hasAuthority('UPDATE_CAMPAIGN')")
    public ResponseEntity<ApiResponse<Void>> removeCreatorFromShortlist(
            @PathVariable UUID campaignId, // path variable exists but unused in service, can be validated if needed
            @PathVariable UUID shortlistItemId,
            @AuthenticationPrincipal User currentUser) {
        shortlistService.removeCreatorFromShortlist(shortlistItemId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.empty());
    }
}
