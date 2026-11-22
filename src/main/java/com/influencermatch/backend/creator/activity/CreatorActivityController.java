package com.influencermatch.backend.creator.activity;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/data/creator-activity")
@PreAuthorize("hasRole('DATA_MANAGER')")
@RequiredArgsConstructor
public class CreatorActivityController {
  private final CreatorActivityService service;

  @GetMapping
  public ApiResponse<PageResponse<Map<String, Object>>> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) CreatorActivityService.Status status,
      @RequestParam(required = false) Boolean hidden) {
    return ApiResponse.ok(service.list(page, size, status, hidden));
  }

  public record VisibilityRequest(
      @NotNull Boolean hidden, @NotBlank @Size(max = 500) String reason) {}

  @PatchMapping("/{creatorId}/visibility")
  public ApiResponse<Map<String, Object>> visibility(
      @PathVariable UUID creatorId, @Valid @RequestBody VisibilityRequest request) {
    service.setVisibility(creatorId, request.hidden(), request.reason());
    return ApiResponse.ok(Map.of("creatorId", creatorId, "hidden", request.hidden()));
  }
}
