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
@RequestMapping("/admin/data-quality")
@RequiredArgsConstructor
@Tag(name = "Data Quality")
@SecurityRequirement(name = "BearerAuth")
@PreAuthorize("hasAuthority('REVIEW_CREATOR_CLASSIFICATION')")
public class DataQualityController {
  private final DataQualityService service;

  @GetMapping("/pending")
  @Operation(summary = "List latest unreviewed KOC snapshots with confidence below 0.7")
  public ApiResponse<PageResponse<QualityItem>> pending(@RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.pending(page, size));
  }

  @PutMapping("/{id}/approve")
  @Operation(summary = "Approve a pending metric snapshot", description = "id is metricId. Empty object keeps Niche; optional niche replaces it. AI confidence is preserved.")
  public ApiResponse<QualityItem> approve(@PathVariable UUID id, @Valid @RequestBody ApproveRequest request) {
    return ApiResponse.ok(service.approve(id, request));
  }
}
