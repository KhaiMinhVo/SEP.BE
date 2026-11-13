package com.influencermatch.backend.cooperation;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/collaborations/{id}")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(
    name = "Collaboration Execution",
    description = "Revision-aware execution; submitted metrics are SELF_REPORTED")
public class CollaborationExecutionController {
  private final CollaborationExecution execution;

  @GetMapping("/deliverables")
  @PreAuthorize("(hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')) or (hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION'))")
  public ApiResponse<JsonNode> read(@PathVariable UUID id) {
    return ApiResponse.ok(execution.read(id));
  }

  @PostMapping("/execution-plan")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')")
  @Operation(summary = "Record explicit execution terms for an unfinished legacy collaboration")
  public ApiResponse<JsonNode> setup(
      @PathVariable UUID id,
      @RequestBody JsonNode body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.setupLegacy(id, body, key));
  }

  @PostMapping("/deliverables/{deliverableId}/submissions")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION')")
  public ApiResponse<JsonNode> submit(
      @PathVariable UUID id,
      @PathVariable UUID deliverableId,
      @Valid @RequestBody ExecutionDtos.Submission body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.submit(id, deliverableId, body, key));
  }

  @PostMapping("/deliverables/{deliverableId}/review")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')")
  public ApiResponse<JsonNode> decide(
      @PathVariable UUID id,
      @PathVariable UUID deliverableId,
      @Valid @RequestBody ExecutionDtos.Decision body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.decide(id, deliverableId, body, key));
  }

  @PostMapping("/deliverables/{deliverableId}/publication")
  @PreAuthorize("(hasRole('BRAND') and hasAuthority('MANAGE_OWN_COLLABORATION')) or (hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION'))")
  public ApiResponse<JsonNode> publication(
      @PathVariable UUID id,
      @PathVariable UUID deliverableId,
      @Valid @RequestBody ExecutionDtos.Publication body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.publish(id, deliverableId, body, key));
  }

  @PostMapping("/outcome")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION')")
  @Operation(
      summary = "Record Creator KPI for current publications",
      description =
          "Send expectedRevision and posts with deliverableId, submissionRevision, periodStart and periodEnd."
              + " Each of views, clicks, likes, comments and shares must be a nonnegative integer or listed"
              + " in unavailableMetrics. Reason is required when updating a saved measurement.")
  public ApiResponse<JsonNode> outcome(
      @PathVariable UUID id,
      @RequestBody JsonNode body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.outcome(id, body, key));
  }

  @PostMapping("/deliverables/{deliverableId}/outcome")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION')")
  @Operation(
      summary = "Record one approved deliverable's publication and Creator KPI",
      description = "Send expectedRevision, submissionRevision, periodStart and periodEnd. Include the HTTPS url"
          + " for a new publication. Declare each of views, clicks, likes, comments and shares as a nonnegative"
          + " integer or by name in unavailableMetrics. evidenceUrl is optional. Include reason when editing"
          + " this submission's saved result. The publication and result are saved together.")
  public ApiResponse<JsonNode> deliverableOutcome(
      @PathVariable UUID id,
      @PathVariable UUID deliverableId,
      @RequestBody JsonNode body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.deliverableOutcome(id, deliverableId, body, key));
  }

  @PostMapping("/outcome/finance")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('RECORD_OWN_OUTCOME')")
  @Operation(summary = "Record Brand finances using the current Creator KPI revision",
      description = "expectedRevision, costs[{description,amount}], currency=VND, gmv?,"
          + " revenue?, periodStart?, periodEnd?, financialEvidence?, financialConfirmed."
          + " Reason is required when editing previously saved finances.")
  public ApiResponse<JsonNode> finance(
      @PathVariable UUID id,
      @RequestBody JsonNode body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.finance(id, body, key));
  }

  @PostMapping("/outcome/accept")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('RECORD_OWN_OUTCOME')")
  public ApiResponse<JsonNode> accept(
      @PathVariable UUID id,
      @Valid @RequestBody ExecutionDtos.Accept body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.accept(id, body, key));
  }

  @PostMapping("/outcome/reopen")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('RECORD_OWN_OUTCOME')")
  public ApiResponse<JsonNode> reopen(
      @PathVariable UUID id,
      @Valid @RequestBody ExecutionDtos.Reopen body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.reopen(id, body, key));
  }

  @PostMapping("/review")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('RECORD_OWN_CREATOR_REVIEW')")
  public ApiResponse<JsonNode> review(
      @PathVariable UUID id,
      @Valid @RequestBody ExecutionDtos.FinalReview body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.review(id, body, key));
  }

  @PostMapping("/review/legacy")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('RECORD_OWN_CREATOR_REVIEW')")
  @Operation(
      summary =
          "Review a historical completed collaboration without fabricating content acceptance")
  public ApiResponse<JsonNode> legacyReview(
      @PathVariable UUID id,
      @Valid @RequestBody ExecutionDtos.LegacyReview body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.legacyReview(id, body, key));
  }
}
