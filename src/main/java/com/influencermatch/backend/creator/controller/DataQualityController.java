package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.*;
import com.influencermatch.backend.creator.dto.DataQualityDtos.*;
import com.influencermatch.backend.creator.service.DataQualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/data/quality", "/data/data-quality", "/admin/data-quality"})
@RequiredArgsConstructor
@Tag(name = "Data Quality", description = "Luồng DM-03: Thẩm định chất lượng Creator cho Data Manager")
@SecurityRequirement(name = "BearerAuth")
@PreAuthorize("hasAuthority('REVIEW_CREATOR_CLASSIFICATION')")
public class DataQualityController {
  private final DataQualityService service;

  @GetMapping("/pending")
  @Operation(summary = "List latest unreviewed KOC/KOL snapshots with low confidence or missing niche")
  public ApiResponse<PageResponse<QualityItem>> pending(@RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.pending(page, size));
  }

  @PostMapping("/review-statuses")
  public ApiResponse<java.util.List<QualityItem>> reviewStatuses(
      @Valid @RequestBody @jakarta.validation.constraints.Size(max = 100)
      java.util.List<@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255) String> names) {
    return ApiResponse.ok(service.reviewStatuses(names));
  }

  @PutMapping("/{id}/approve")
  @Operation(summary = "Approve a pending metric snapshot", description = "id is metricId. Empty object keeps Niche; optional niche replaces it. AI confidence is preserved.")
  public ApiResponse<QualityItem> approve(@PathVariable UUID id, @Valid @RequestBody ApproveRequest request) {
    return ApiResponse.ok(service.approve(id, request));
  }

  @PutMapping("/{id}/reject")
  @Operation(summary = "Reject or reclassify a pending metric snapshot", description = "id is metricId. Requires a reason. Allowed types: BRAND, REJECTED, KOC, KOL. Records decision in audit log.")
  public ApiResponse<QualityItem> reject(@PathVariable UUID id, @Valid @RequestBody(required = false) RejectRequest request) {
    return ApiResponse.ok(service.reject(id, request != null ? request : new RejectRequest(null, null)));
  }
}
