package com.influencermatch.backend.brand.controller;

import com.influencermatch.backend.brand.dto.*;
import com.influencermatch.backend.brand.enums.*;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.brand.service.*;
import com.influencermatch.backend.common.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/brands")
@RequiredArgsConstructor
@Tag(name = "Brand Profiles", description = "Brand profile and M4 profile context management")
@SecurityRequirement(name = "BearerAuth")
public class BrandController {
  private final BrandService service;

  @PostMapping
  @Operation(summary = "Create a brand profile")
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CREATE_BRAND_PROFILE')")
  public ResponseEntity<ApiResponse<BrandResponse>> create(
      @Valid @RequestBody BrandRequest request, Authentication auth) {
    BrandResponse result = service.create(request, auth);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(result.id())
            .toUri();
    return ResponseEntity.created(location).body(ApiResponse.ok(result));
  }

  @Operation(summary = "Get the current brand profile")
  @GetMapping("/me")
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('VIEW_OWN_BRAND_DATA')")
  public ApiResponse<BrandResponse> me(Authentication auth) {
    return ApiResponse.ok(service.me(auth));
  }

  @Operation(summary = "List accessible brand profiles")
  @GetMapping
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_OWN_BRAND_DATA') or hasAuthority('VIEW_BRAND_SUPPORT_DATA')")
  public ApiResponse<PageResponse<BrandResponse>> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication auth) {
    return ApiResponse.ok(
        service.list(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)), auth));
  }

  @Operation(summary = "Get a brand profile")
  @GetMapping("/{id}")
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_OWN_BRAND_DATA') or hasAuthority('VIEW_BRAND_SUPPORT_DATA')")
  public ApiResponse<BrandResponse> get(@PathVariable UUID id, Authentication auth) {
    return ApiResponse.ok(service.get(id, auth));
  }

  @Operation(summary = "Update a brand profile")
  @PutMapping("/{id}")
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  public ApiResponse<BrandResponse> update(
      @PathVariable UUID id, @Valid @RequestBody BrandRequest request, Authentication auth) {
    return ApiResponse.ok(service.update(id, request, auth));
  }
}
