package com.influencermatch.backend.taxonomy.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.taxonomy.dto.NicheRequest;
import com.influencermatch.backend.taxonomy.model.Niche;
import com.influencermatch.backend.taxonomy.service.NicheService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/data/niches")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DATA_MANAGER', 'ADMIN')")
@Tag(name = "Quản lý ngành hàng")
public class NicheManagementController {
  private final NicheService service;

  @GetMapping
  public ApiResponse<List<Niche>> list() { return ApiResponse.ok(service.list()); }

  @PostMapping
  public ApiResponse<Niche> create(@Valid @RequestBody NicheRequest request) {
    return ApiResponse.ok(service.create(request));
  }

  @PutMapping("/{id}")
  public ApiResponse<Niche> update(@PathVariable UUID id, @Valid @RequestBody NicheRequest request) {
    return ApiResponse.ok(service.update(id, request));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ApiResponse.empty();
  }
}
