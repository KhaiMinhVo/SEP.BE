package com.influencermatch.backend.user.controller;
import com.influencermatch.backend.user.model.*;
import com.influencermatch.backend.user.repository.*;
import com.influencermatch.backend.user.service.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.dto.*;
import com.influencermatch.backend.user.controller.*;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.auth.dto.AdminUserResponse;
import com.influencermatch.backend.auth.dto.UpdateUserStatusRequest;
import com.influencermatch.backend.user.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin Users", description = "ADMIN-only account management")
@SecurityRequirement(name = "BearerAuth")
public class AdminUserController {
    private final AdminUserService service;

    @Operation(summary = "List users")
    @GetMapping
    public ApiResponse<PageResponse<AdminUserResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResponse.from(service.list(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)))));
    }

    @Operation(summary = "Get a user")
    @GetMapping("/{id}")
    public ApiResponse<AdminUserResponse> get(@PathVariable UUID id) { return ApiResponse.ok(service.get(id)); }

    @Operation(summary = "Change account status", description = "ACTIVE unlocks; LOCKED and DISABLED revoke all refresh tokens")
    @PatchMapping("/{id}/status")
    public ApiResponse<AdminUserResponse> status(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateUserStatusRequest request) {
        return ApiResponse.ok(service.changeStatus(id, request));
    }
}


