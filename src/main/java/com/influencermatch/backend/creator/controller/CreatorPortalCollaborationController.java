package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.collaboration.dto.CreatorCollaborationDto;
import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import com.influencermatch.backend.collaboration.service.CollaborationService;
import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/creators/portal/collaborations")
@RequiredArgsConstructor
@Tag(
    name = "Creator Portal Collaborations",
    description = "Collaborations and campaign assignments for Creator Master")
@SecurityRequirement(name = "BearerAuth")
public class CreatorPortalCollaborationController {

  private final CollaborationService collaborationService;

  @GetMapping
  @PreAuthorize("hasRole('CREATOR')")
  @Operation(
      summary = "Get collaboration list for authenticated creator",
      description =
          "Returns all collaborations assigned to the creator's master identity across all linked channels.")
  public ApiResponse<List<CreatorCollaborationDto>> getMyCollaborations(
      @RequestParam(name = "status", required = false) CollaborationStatus status) {
    return ApiResponse.ok(collaborationService.getCreatorPortalCollaborations(status));
  }
}
