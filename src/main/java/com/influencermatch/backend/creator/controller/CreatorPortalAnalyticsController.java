package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.dto.AggregatedReachDto;
import com.influencermatch.backend.creator.service.CreatorAccountLinkService;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/creators/portal/analytics")
@RequiredArgsConstructor
@Tag(name = "Creator Portal Analytics", description = "Cross-platform analytics and aggregated reach for Creator Master")
@SecurityRequirement(name = "BearerAuth")
public class CreatorPortalAnalyticsController {

  private final CreatorAccountLinkService accountLinkService;

  @GetMapping("/reach")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @Operation(summary = "Get aggregated multi-channel reach metrics across all linked channels for current creator master")
  public ApiResponse<AggregatedReachDto> getAggregatedReach() {
    User actor = Permissions.actor();
    AggregatedReachDto dto = accountLinkService.getAggregatedReach(actor.getId());
    return ApiResponse.ok(dto);
  }
}
