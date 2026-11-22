package com.influencermatch.backend.cooperation;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/collaborations/{id}")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class FlexibleExecutionController {
  private final FlexibleExecution flexible;
  private final CollaborationExecution execution;

  @PostMapping("/management-mode")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')")
  public ApiResponse<JsonNode> mode(@PathVariable UUID id, @Valid @RequestBody FlexibleDtos.Mode body,
      @RequestHeader("Idempotency-Key") String key) { return ApiResponse.ok(flexible.mode(id, body, key)); }

  @PostMapping("/deliverables/{unit}/external-submissions")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')")
  public ApiResponse<JsonNode> submit(@PathVariable UUID id, @PathVariable UUID unit,
      @Valid @RequestBody FlexibleDtos.ExternalSubmission body, @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.submitExternal(id, unit, body, key));
  }
  @PostMapping("/deliverables/{unit}/external-publication")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')")
  public ApiResponse<JsonNode> publish(@PathVariable UUID id, @PathVariable UUID unit,
      @Valid @RequestBody FlexibleDtos.ExternalPublication body, @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.publishExternal(id, unit, body, key));
  }
  @PostMapping("/deliverables/{unit}/external-outcome")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('RECORD_OWN_OUTCOME') and hasAuthority('MANAGE_OWN_COLLABORATION')")
  public ApiResponse<JsonNode> outcome(@PathVariable UUID id, @PathVariable UUID unit,
      @RequestBody JsonNode body, @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.deliverableOutcomeExternal(id, unit, body, key));
  }
  @PostMapping("/complete-without-review")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('RECORD_OWN_OUTCOME') and hasAuthority('MANAGE_OWN_COLLABORATION')")
  public ApiResponse<JsonNode> complete(@PathVariable UUID id, @Valid @RequestBody FlexibleDtos.Complete body,
      @RequestHeader("Idempotency-Key") String key) { return ApiResponse.ok(flexible.completeWithoutReview(id, body, key)); }

  @PostMapping("/termination-requests")
  @PreAuthorize("(hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')) or"
      + " (hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION'))")
  public ApiResponse<JsonNode> request(@PathVariable UUID id, @Valid @RequestBody FlexibleDtos.Exit body,
      @RequestHeader("Idempotency-Key") String key) { return ApiResponse.ok(flexible.requestTermination(id, body, key)); }

  @PostMapping("/terminate")
  @PreAuthorize("(hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')) or"
      + " (hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION'))")
  public ApiResponse<JsonNode> terminate(@PathVariable UUID id, @Valid @RequestBody FlexibleDtos.Exit body,
      @RequestHeader("Idempotency-Key") String key) { return ApiResponse.ok(flexible.terminate(id, body, key, false)); }

  @PostMapping("/termination-requests/decline")
  @PreAuthorize("(hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')) or"
      + " (hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION'))")
  public ApiResponse<JsonNode> decline(@PathVariable UUID id, @Valid @RequestBody FlexibleDtos.Exit body,
      @RequestHeader("Idempotency-Key") String key) { return ApiResponse.ok(flexible.terminate(id, body, key, true)); }
}
