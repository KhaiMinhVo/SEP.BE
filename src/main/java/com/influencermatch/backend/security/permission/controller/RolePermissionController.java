package com.influencermatch.backend.security.permission.controller;

import static com.influencermatch.backend.security.permission.dto.RolePermissionDtos.*;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.security.permission.service.RolePermissionService;
import com.influencermatch.backend.user.enums.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/role-permissions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Role Permissions")
@SecurityRequirement(name = "BearerAuth")
public class RolePermissionController {
  private final RolePermissionService service;

  @ModelAttribute
  public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "private, no-store");
  }

  @GetMapping
  @Operation(summary = "View default and effective permissions for all roles")
  public ApiResponse<List<RoleView>> catalog() {
    return ApiResponse.ok(service.catalog());
  }

  @PatchMapping("/{role}")
  @Operation(
      summary = "Enable or disable existing role capabilities",
      description =
          "ADMIN + ASSIGN_USER_ROLE. Only default, implemented capabilities of"
              + " BRAND/CREATOR/DATA_MANAGER are editable; ADMIN is immutable. Requires current"
              + " config version; stale version returns 409. Applies next request with the same"
              + " JWT; does not revoke all sessions of the role.")
  public ApiResponse<RoleView> update(@PathVariable Role role, @Valid @RequestBody Update request) {
    return ApiResponse.ok(service.update(role, request));
  }
}
