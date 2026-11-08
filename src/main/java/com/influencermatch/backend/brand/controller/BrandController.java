package com.influencermatch.backend.brand.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.brand.dto.*;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/brands")
@RequiredArgsConstructor
@Tag(
    name = "Brand Profiles",
    description = "Brand profiles, immutable M4 context and ETag-protected updates")
@SecurityRequirement(name = "BearerAuth")
public class BrandController {
  private final BrandService service;

  private ResponseEntity<ApiResponse<BrandResponse>> ok(BrandResponse r) {
    return ResponseEntity.ok()
        .eTag(BrandRevision.tag(r.id(), r.profileRevision()))
        .body(ApiResponse.ok(r));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('CREATE_BRAND_PROFILE')")
  @Operation(summary = "Create a profile for a historical Brand without one")
  public ResponseEntity<ApiResponse<BrandResponse>> create(
      @Valid @RequestBody BrandRequest request, Authentication auth) {
    var r = service.create(request, auth);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(r.id())
            .toUri();
    return ResponseEntity.created(location)
        .eTag(BrandRevision.tag(r.id(), r.profileRevision()))
        .body(ApiResponse.ok(r));
  }

  @GetMapping("/me")
  @PreAuthorize("hasAuthority('VIEW_OWN_BRAND_DATA')")
  public ResponseEntity<ApiResponse<BrandResponse>> me(Authentication auth) {
    return ok(service.me(auth));
  }

  @GetMapping
  @PreAuthorize("hasAuthority('VIEW_OWN_BRAND_DATA') or hasAuthority('VIEW_BRAND_SUPPORT_DATA')")
  public ApiResponse<PageResponse<BrandResponse>> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication auth) {
    return ApiResponse.ok(
        service.list(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)), auth));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('VIEW_OWN_BRAND_DATA') or hasAuthority('VIEW_BRAND_SUPPORT_DATA')")
  public ResponseEntity<ApiResponse<BrandResponse>> get(
      @PathVariable UUID id, Authentication auth) {
    return ok(service.get(id, auth));
  }

  @PostMapping("/me/onboarding/complete")
  @PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  @Operation(summary = "Complete onboarding; current If-Match required")
  public ResponseEntity<ApiResponse<BrandResponse>> complete(
      Authentication auth, @RequestHeader(value = "If-Match", required = false) String revision) {
    return ok(service.complete(auth, revision));
  }

  @PutMapping("/me")
  @PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  @Operation(summary = "Replace full profile; current If-Match required")
  public ResponseEntity<ApiResponse<BrandResponse>> updateMe(
      @Valid @RequestBody BrandRequest request,
      Authentication auth,
      @RequestHeader(value = "If-Match", required = false) String revision) {
    return ok(service.updateMe(request, auth, revision));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  public ResponseEntity<ApiResponse<BrandResponse>> update(
      @PathVariable UUID id,
      @Valid @RequestBody BrandRequest request,
      Authentication auth,
      @RequestHeader(value = "If-Match", required = false) String revision) {
    return ok(service.update(id, request, auth, revision));
  }

  @PatchMapping("/me")
  @PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  @Operation(summary = "Patch supplied fields only; null clears optional data; If-Match required")
  public ResponseEntity<ApiResponse<BrandResponse>> patch(
      @RequestBody JsonNode patch,
      Authentication auth,
      @RequestHeader(value = "If-Match", required = false) String revision) {
    return ok(service.patchMe(patch, auth, revision));
  }

  @PostMapping("/me/context/embedding/retry")
  @PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  @Operation(summary = "Retry FAILED embedding for the current context and configured model")
  public ResponseEntity<ApiResponse<BrandResponse>> retry(Authentication auth) {
    return ok(service.retryEmbedding(auth));
  }
}
