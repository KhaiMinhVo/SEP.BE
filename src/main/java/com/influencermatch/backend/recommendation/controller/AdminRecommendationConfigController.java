package com.influencermatch.backend.recommendation.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.recommendation.dto.RecommendationConfigResponseDto;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Luồng DM-04: Tra cứu Cấu hình Thuật toán Four-Memory (View Recommendation Configuration).
 * Dedicated for ADMIN and DATA_MANAGER roles to inspect active calibration & hyperparameters.
 */
@RestController
@RequestMapping("/admin/recommendation-config")
@RequiredArgsConstructor
@Tag(
    name = "Recommendation Config",
    description = "MANAGE_RECOMMENDATION_CONFIG; Read-only configuration inspector for Four-Memory Engine")
public class AdminRecommendationConfigController {

  @GetMapping
  @PreAuthorize("hasAuthority('MANAGE_RECOMMENDATION_CONFIG')")
  @Operation(
      summary = "Get active Four-Memory recommendation engine configuration",
      description = "Inspect calibration version, half-saturation constants, stability floor, dynamic weights lambda, and MMR hyperparameter.",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<ApiResponse<RecommendationConfigResponseDto>> getConfiguration() {
    Permissions.require(Permission.MANAGE_RECOMMENDATION_CONFIG);
    return ResponseEntity.ok(ApiResponse.ok(RecommendationConfigResponseDto.createCurrent()));
  }
}

