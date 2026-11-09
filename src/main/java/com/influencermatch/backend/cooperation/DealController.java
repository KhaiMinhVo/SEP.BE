package com.influencermatch.backend.cooperation;

import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(
    name = "Cooperation Deals",
    description = "Channel-grouped proposals, consent and budget-safe confirmation")
@SecurityRequirement(name = "BearerAuth")
public class DealController {
  final DealService deals;
  final CooperationDelivery delivery;

  @PostMapping("/campaigns/{campaignId}/deals")
  @PreAuthorize("hasAuthority('MANAGE_OWN_RELATIONSHIP')")
  @Operation(
      summary = "Create a deal from independent channel relationships",
      description = "Requires Idempotency-Key. Multiple channels require one verified Master.")
  public ApiResponse<?> create(
      @PathVariable UUID campaignId,
      @Valid @RequestBody CooperationDtos.Create body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return ApiResponse.ok(deals.create(campaignId, body, key));
  }

  @GetMapping("/campaigns/{campaignId}/deals")
  @PreAuthorize("hasAuthority('MANAGE_OWN_RELATIONSHIP')")
  public ApiResponse<?> list(@PathVariable UUID campaignId) {
    return ApiResponse.ok(deals.list(campaignId));
  }

  @GetMapping("/campaigns/{campaignId}/deals/{dealId}")
  @PreAuthorize("hasAuthority('MANAGE_OWN_RELATIONSHIP')")
  public ApiResponse<?> detail(@PathVariable UUID campaignId, @PathVariable UUID dealId) {
    return ApiResponse.ok(deals.get(campaignId, dealId));
  }

  @GetMapping("/campaigns/{campaignId}/deals/{dealId}/history")
  @PreAuthorize("hasAuthority('MANAGE_OWN_RELATIONSHIP')")
  public ApiResponse<?> history(@PathVariable UUID campaignId, @PathVariable UUID dealId) {
    return ApiResponse.ok(deals.history(campaignId, dealId));
  }

  @PostMapping("/campaigns/{campaignId}/deals/{dealId}/proposals")
  @PreAuthorize("hasAuthority('MANAGE_OWN_RELATIONSHIP')")
  public ApiResponse<?> proposal(
      @PathVariable UUID campaignId,
      @PathVariable UUID dealId,
      @Valid @RequestBody CooperationDtos.Proposal body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return ApiResponse.ok(deals.propose(campaignId, dealId, body, key));
  }

  @PostMapping("/campaigns/{campaignId}/deals/{dealId}/invitations")
  @PreAuthorize("hasAuthority('MANAGE_OWN_RELATIONSHIP')")
  public ApiResponse<?> send(
      @PathVariable UUID campaignId,
      @PathVariable UUID dealId,
      @Valid @RequestBody CooperationDtos.Send body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return ApiResponse.ok(deals.send(campaignId, dealId, body, key));
  }

  @PostMapping("/campaigns/{campaignId}/deals/{dealId}/confirm")
  @PreAuthorize(
      "hasAuthority('MANAGE_OWN_RELATIONSHIP') and hasAuthority('MANAGE_OWN_COLLABORATION')")
  @Operation(
      summary = "Confirm accepted or externally evidenced terms atomically",
      description =
          "Creates one PLANNED Collaboration. VND budget is enforced; this is not payment or"
              + " channel ownership.")
  public ApiResponse<?> confirm(
      @PathVariable UUID campaignId,
      @PathVariable UUID dealId,
      @Valid @RequestBody CooperationDtos.Confirm body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return ApiResponse.ok(deals.confirm(campaignId, dealId, body, key));
  }

  @PostMapping("/campaigns/{campaignId}/deals/{dealId}/start")
  @PreAuthorize(
      "hasAuthority('MANAGE_OWN_RELATIONSHIP') and hasAuthority('MANAGE_OWN_COLLABORATION')")
  public ApiResponse<?> start(
      @PathVariable UUID campaignId,
      @PathVariable UUID dealId,
      @Valid @RequestBody CooperationDtos.Revision body) {
    return ApiResponse.ok(
        deals.transition(
            campaignId,
            dealId,
            "start",
            new CooperationDtos.Cancel(
                body.expectedRevision(), "Brand started the planned collaboration")));
  }

  @PostMapping("/campaigns/{campaignId}/deals/{dealId}/cancel")
  @PreAuthorize(
      "hasAuthority('MANAGE_OWN_RELATIONSHIP') and hasAuthority('MANAGE_OWN_COLLABORATION')")
  public ApiResponse<?> cancel(
      @PathVariable UUID campaignId,
      @PathVariable UUID dealId,
      @Valid @RequestBody CooperationDtos.Cancel body) {
    return ApiResponse.ok(deals.transition(campaignId, dealId, "cancel", body));
  }

  @GetMapping("/campaigns/{campaignId}/cooperation-budget")
  @PreAuthorize("hasAuthority('MANAGE_OWN_RELATIONSHIP')")
  public ApiResponse<?> budget(@PathVariable UUID campaignId) {
    return ApiResponse.ok(deals.budget(campaignId));
  }

  @PostMapping("/campaigns/{campaignId}/cooperation-budget")
  @PreAuthorize("hasAuthority('UPDATE_CAMPAIGN')")
  public org.springframework.http.ResponseEntity<?> setBudget(
      @PathVariable UUID campaignId,
      @Valid @RequestBody CooperationDtos.Budget body,
      @RequestHeader(value = "If-Match", required = false) String match) {
    var result = (Map<?, ?>) deals.setBudget(campaignId, body, match);
    return org.springframework.http.ResponseEntity.ok()
        .eTag(result.get("etag").toString())
        .body(ApiResponse.ok(result));
  }

  @GetMapping("/cooperation-invitations/preview")
  @Operation(
      summary = "Public minimal invitation preview; never grants ownership or accepts a deal")
  public ApiResponse<?> preview(@RequestParam String token) {
    return ApiResponse.ok(delivery.preview(token));
  }

  @GetMapping("/cooperation-invitations/{id}/manual-link")
  @PreAuthorize("hasAuthority('MANAGE_OWN_RELATIONSHIP')")
  public ApiResponse<?> manual(@PathVariable UUID id) {
    return ApiResponse.ok(delivery.manualLink(id));
  }

  @GetMapping("/cooperation-invitations/{id}/email-delivery")
  @PreAuthorize("hasAuthority('MANAGE_OWN_RELATIONSHIP')")
  public ApiResponse<?> status(@PathVariable UUID id) {
    return ApiResponse.ok(delivery.status(id));
  }

  @PostMapping("/cooperation-invitations/{id}/retry-email")
  @PreAuthorize("hasAuthority('MANAGE_OWN_RELATIONSHIP')")
  public ApiResponse<?> retry(@PathVariable UUID id) {
    return ApiResponse.ok(delivery.retry(id));
  }

  @GetMapping("/creators/portal/cooperation-deals")
  @PreAuthorize("hasAuthority('RESPOND_OWN_COOPERATION')")
  public ApiResponse<?> portalList() {
    return ApiResponse.ok(deals.portalList());
  }

  @GetMapping("/creators/portal/cooperation-deals/{id}")
  @PreAuthorize("hasAuthority('RESPOND_OWN_COOPERATION')")
  public ApiResponse<?> portalDetail(@PathVariable UUID id) {
    return ApiResponse.ok(deals.portalGet(id));
  }

  @PostMapping("/creators/portal/cooperation-deals/{id}/accept")
  @PreAuthorize("hasAuthority('RESPOND_OWN_COOPERATION')")
  public ApiResponse<?> accept(
      @PathVariable UUID id, @Valid @RequestBody CooperationDtos.Revision body) {
    return ApiResponse.ok(deals.respond(id, "accept", body));
  }

  @PostMapping("/creators/portal/cooperation-deals/{id}/decline")
  @PreAuthorize("hasAuthority('RESPOND_OWN_COOPERATION')")
  public ApiResponse<?> decline(
      @PathVariable UUID id, @Valid @RequestBody CooperationDtos.Revision body) {
    return ApiResponse.ok(deals.respond(id, "decline", body));
  }

  @PostMapping("/creators/portal/cooperation-deals/{id}/counteroffers")
  @PreAuthorize("hasAuthority('RESPOND_OWN_COOPERATION')")
  public ApiResponse<?> counter(
      @PathVariable UUID id,
      @Valid @RequestBody CooperationDtos.Proposal body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return ApiResponse.ok(deals.counter(id, body, key));
  }

  @GetMapping("/creators/portal/notifications")
  @PreAuthorize("hasAuthority('VIEW_OWN_NOTIFICATION')")
  public ApiResponse<?> notifications() {
    return ApiResponse.ok(deals.notifications());
  }

  @PostMapping("/creators/portal/notifications/{id}/read")
  @PreAuthorize("hasAuthority('MANAGE_OWN_NOTIFICATION')")
  public ApiResponse<?> read(@PathVariable UUID id) {
    return ApiResponse.ok(deals.readNotification(id));
  }

  @GetMapping("/creators/portal/cooperation-deals/{id}/history")
  @PreAuthorize("hasAuthority('RESPOND_OWN_COOPERATION')")
  public ApiResponse<?> portalHistory(@PathVariable UUID id) {
    return ApiResponse.ok(deals.portalHistory(id));
  }
}
