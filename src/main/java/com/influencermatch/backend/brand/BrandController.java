package com.influencermatch.backend.brand;

import com.influencermatch.backend.brand.dto.*;
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
@RequestMapping("/brands")
@RequiredArgsConstructor
public class BrandController {
    private final BrandService service;

    @PostMapping
    public ResponseEntity<ApiResponse<BrandResponse>> create(@Valid @RequestBody BrandRequest request, Authentication auth) {
        BrandResponse result = service.create(request, auth);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(result.id()).toUri();
        return ResponseEntity.created(location).body(ApiResponse.ok(result));
    }
    @GetMapping("/me") public ApiResponse<BrandResponse> me(Authentication auth) { return ApiResponse.ok(service.me(auth)); }
    @GetMapping public ApiResponse<PageResponse<BrandResponse>> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size, Authentication auth) {
        return ApiResponse.ok(service.list(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)), auth));
    }
    @GetMapping("/{id}") public ApiResponse<BrandResponse> get(@PathVariable UUID id, Authentication auth) { return ApiResponse.ok(service.get(id, auth)); }
    @PutMapping("/{id}") public ApiResponse<BrandResponse> update(@PathVariable UUID id, @Valid @RequestBody BrandRequest request, Authentication auth) { return ApiResponse.ok(service.update(id, request, auth)); }
}
