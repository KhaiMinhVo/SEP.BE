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
  @PreAuthorize("hasAnyAuthority('MANAGE_OWN_COLLABORATION','RESPOND_OWN_COOPERATION')")
  public ApiResponse<JsonNode> read(@PathVariable UUID id) {
    return ApiResponse.ok(execution.read(id));
  }

  @PostMapping("/execution-plan")
  @PreAuthorize("hasAuthority('MANAGE_OWN_COLLABORATION')")
  @Operation(summary = "Record explicit execution terms for an unfinished legacy collaboration")
  public ApiResponse<JsonNode> setup(
      @PathVariable UUID id,
      @RequestBody JsonNode body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.setupLegacy(id, body, key));
  }

  @PostMapping("/deliverables/{deliverableId}/submissions")
  @PreAuthorize("hasAnyAuthority('MANAGE_OWN_COLLABORATION','RESPOND_OWN_COOPERATION')")
  public ApiResponse<JsonNode> submit(
      @PathVariable UUID id,
      @PathVariable UUID deliverableId,
      @Valid @RequestBody ExecutionDtos.Submission body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.submit(id, deliverableId, body, key));
  }

  @PostMapping("/deliverables/{deliverableId}/review")
  @PreAuthorize("hasAuthority('MANAGE_OWN_COLLABORATION')")
  public ApiResponse<JsonNode> decide(
      @PathVariable UUID id,
      @PathVariable UUID deliverableId,
      @Valid @RequestBody ExecutionDtos.Decision body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.decide(id, deliverableId, body, key));
  }

  @PostMapping("/deliverables/{deliverableId}/publication")
  @PreAuthorize("hasAnyAuthority('MANAGE_OWN_COLLABORATION','RESPOND_OWN_COOPERATION')")
  public ApiResponse<JsonNode> publication(
      @PathVariable UUID id,
      @PathVariable UUID deliverableId,
      @Valid @RequestBody ExecutionDtos.Publication body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.publish(id, deliverableId, body, key));
  }

  @PostMapping("/outcome")
  @PreAuthorize("hasAnyAuthority('RECORD_OWN_OUTCOME','RESPOND_OWN_COOPERATION')")
  @Operation(
      summary = "Append an outcome revision without completing collaboration",
      description =
          "expectedRevision, reason,"
              + " posts[{deliverableId,submissionRevision,periodStart,periodEnd,views?,clicks?,likes?,comments?,shares?,evidenceUrl?}]."
              + " Brand additionally supplies costs[{description,amount}], currency=VND, gmv?,"
              + " revenue?, periodStart, periodEnd, financialEvidence?, financialConfirmed.")
  public ApiResponse<JsonNode> outcome(
      @PathVariable UUID id,
      @RequestBody JsonNode body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.outcome(id, body, key));
  }

  @PostMapping("/outcome/accept")
  @PreAuthorize("hasAuthority('RECORD_OWN_OUTCOME')")
  public ApiResponse<JsonNode> accept(
      @PathVariable UUID id,
      @Valid @RequestBody ExecutionDtos.Accept body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.accept(id, body, key));
  }

  @PostMapping("/outcome/reopen")
  @PreAuthorize("hasAuthority('RECORD_OWN_OUTCOME')")
  public ApiResponse<JsonNode> reopen(
      @PathVariable UUID id,
      @Valid @RequestBody ExecutionDtos.Reopen body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.reopen(id, body, key));
  }

  @PostMapping("/review")
  @PreAuthorize("hasAuthority('RECORD_OWN_CREATOR_REVIEW')")
  public ApiResponse<JsonNode> review(
      @PathVariable UUID id,
      @Valid @RequestBody ExecutionDtos.FinalReview body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(execution.review(id, body, key));
  }

  @PostMapping("/review/legacy")
  @PreAuthorize("hasAuthority('RECORD_OWN_CREATOR_REVIEW')")
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
