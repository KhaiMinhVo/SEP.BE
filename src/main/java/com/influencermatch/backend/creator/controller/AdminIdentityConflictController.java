package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.creator.dto.ConflictDetailDto;
import com.influencermatch.backend.creator.dto.ResolveConflictRequest;
import com.influencermatch.backend.creator.service.CreatorIdentityConflictService;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/creator-conflicts")
@RequiredArgsConstructor
@Tag(name = "Admin Creator Identity Conflicts")
@SecurityRequirement(name = "BearerAuth")
public class AdminIdentityConflictController {

  private final CreatorIdentityConflictService conflictService;

  @GetMapping
  @PreAuthorize("hasAuthority('REVIEW_CREATOR_CLAIM') or hasAuthority('RESOLVE_CREATOR_DUPLICATE')")
  @Operation(summary = "Read historical creator identity conflicts; transfer resolution is unavailable in MVP")
  public ApiResponse<PageResponse<ConflictDetailDto>> listConflicts(
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "20") int size) {

    Pageable pageable = PageRequest.of(page, size);
    Page<ConflictDetailDto> result = conflictService.listConflicts(status, pageable);
    return ApiResponse.ok(PageResponse.from(result));
  }

}
