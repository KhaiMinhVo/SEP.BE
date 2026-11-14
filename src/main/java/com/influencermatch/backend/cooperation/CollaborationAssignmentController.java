package com.influencermatch.backend.cooperation;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/creators/portal/collaborations")
@PreAuthorize("hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION')")
@RequiredArgsConstructor
public class CollaborationAssignmentController {
  private final DealService deals;

  @GetMapping("/pending-assignments")
  @Operation(summary = "List confirmed agreements awaiting this verified Creator's consent")
  public ApiResponse<List<Map<String, Object>>> pending() {
    return ApiResponse.ok(deals.pendingAssignments());
  }

  @PostMapping("/{id}/accept-assignment")
  @Operation(summary = "Accept the confirmed terms and take an existing planned assignment")
  public ApiResponse<JsonNode> accept(
      @PathVariable UUID id,
      @Valid @RequestBody ExecutionDtos.Assignment request,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(deals.acceptAssignment(id, request, key));
  }
}
