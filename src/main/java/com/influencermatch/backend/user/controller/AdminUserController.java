package com.influencermatch.backend.user.controller;

import com.influencermatch.backend.auth.dto.AdminUserResponse;
import com.influencermatch.backend.auth.dto.UpdateUserStatusRequest;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.*;
import com.influencermatch.backend.user.repository.*;
import com.influencermatch.backend.user.service.*;
import com.influencermatch.backend.user.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin Users", description = "ADMIN-only account management")
@SecurityRequirement(name = "BearerAuth")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
  private final AdminUserService service;

  @ModelAttribute
  public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "private, no-store");
  }

  @Operation(
      summary = "Assign a fixed role",
      description =
          "ASSIGN_USER_ROLE; reason required (1-500 trimmed characters). Any real role change"
              + " revokes sessions and refresh tokens. Cannot remove the last active Admin,"
              + " reassign a Brand with a profile away from BRAND, or reassign a Creator with"
              + " owned channels/pending claims away from CREATOR. No If-Match/version required.")
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasRole('ADMIN') and hasAuthority('ASSIGN_USER_ROLE')")
  @PatchMapping("/{id}/role")
  public ApiResponse<AdminUserResponse> role(
      @PathVariable UUID id,
      @Valid @RequestBody com.influencermatch.backend.user.dto.UpdateUserRoleRequest request) {
    return ApiResponse.ok(service.changeRole(id, request));
  }

  @Operation(
      summary = "List users",
      description =
          "Literal case-insensitive q over email/full name or exact UUID; optional role/status;"
              + " page>=0, size 1-100. Stable createdAt/id descending order.")
  @GetMapping
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasRole('ADMIN') and hasAuthority('VIEW_USER')")
  public ApiResponse<PageResponse<AdminUserResponse>> list(
      @RequestParam(required = false) Role role,
      @RequestParam(required = false) UserStatus status,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    UserAccountSearch.page(page, size);
    UserAccountSearch.query(q);
    return ApiResponse.ok(PageResponse.from(service.list(role, status, q, page, size)));
  }

  @Operation(summary = "Get a user")
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasRole('ADMIN') and hasAuthority('VIEW_USER')")
  @GetMapping("/{id}")
  public ApiResponse<AdminUserResponse> get(@PathVariable UUID id) {
    return ApiResponse.ok(service.get(id));
  }

  @Operation(
      summary = "Change account status",
      description =
          "Reason required (1-500 trimmed characters), including no-op requests. Any real status"
              + " change revokes all sessions and refresh tokens; unlock does not restore old"
              + " sessions.")
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasRole('ADMIN') and hasAuthority('CHANGE_USER_STATUS')")
  @PatchMapping("/{id}/status")
  public ApiResponse<AdminUserResponse> status(
      @PathVariable UUID id, @Valid @RequestBody UpdateUserStatusRequest request) {
    return ApiResponse.ok(service.changeStatus(id, request));
  }
}
