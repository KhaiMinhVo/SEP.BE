package com.influencermatch.backend.campaign;

import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class CampaignController {
    private final CampaignService service;

    @PostMapping("/brands/{brandId}/campaigns")
    public ResponseEntity<ApiResponse<CampaignResponse>> create(@PathVariable UUID brandId, @Valid @RequestBody CampaignRequest request, Authentication auth) {
        CampaignResponse result = service.create(brandId, request, auth);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/campaigns/{id}").buildAndExpand(result.id()).toUri();
        return ResponseEntity.created(location).body(ApiResponse.ok(result));
    }
    @GetMapping("/brands/{brandId}/campaigns") public ApiResponse<PageResponse<CampaignResponse>> list(@PathVariable UUID brandId, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size, Authentication auth) {
        return ApiResponse.ok(service.list(brandId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)), auth));
    }
    @GetMapping("/campaigns/{id}") public ApiResponse<CampaignResponse> get(@PathVariable UUID id, Authentication auth) { return ApiResponse.ok(service.get(id, auth)); }
    @PutMapping("/campaigns/{id}") public ApiResponse<CampaignResponse> update(@PathVariable UUID id, @Valid @RequestBody CampaignRequest request, Authentication auth) { return ApiResponse.ok(service.update(id, request, auth)); }
    @PatchMapping("/campaigns/{id}/archive") public ApiResponse<CampaignResponse> archive(@PathVariable UUID id, Authentication auth) { return ApiResponse.ok(service.archive(id, auth)); }
}
